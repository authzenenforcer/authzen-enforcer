package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.EnforcementResult;
import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.core.enforce.PermissionSpec;
import io.github.authzenenforcer.core.enforce.RequestDescription;
import io.github.authzenenforcer.spring.servlet.ClaimsExtractor;
import io.github.authzenenforcer.spring.servlet.EnforcementResultHandler;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.lang.System.Logger.Level;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks the {@link Enforcer} before an enforced handler method runs, and lets the
 * {@link EnforcementResultHandler} answer requests it does not let through.
 */
public final class PermissionInterceptor implements HandlerInterceptor {

    private static final System.Logger LOGGER = System.getLogger(PermissionInterceptor.class.getName());

    // Messages name handlers and exception types, never request values, so there are only as many
    // as there are handlers; the cap only guards against a custom claims extractor
    private static final int MAX_WARNED = 1000;

    // The handler, route and path variables a request was let through to; request attributes cannot be
    // set by the caller
    private static final String PASSED = PermissionInterceptor.class.getName() + ".PASSED";

    // Answers an OPTIONS request that no mapping declares with the Allow header; no application code
    // runs, and it has no route pattern to infer from
    private static final String OPTIONS_HANDLER =
            "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping$HttpOptionsHandler";

    private final Enforcer enforcer;
    private final HandlerPermissions permissions;
    private final ClaimsExtractor claimsExtractor;
    private final EnforcementResultHandler resultHandler;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * Creates the interceptor.
     *
     * @param enforcer        the enforcer
     * @param permissions     how handlers are enforced
     * @param claimsExtractor gets the caller's claims
     * @param resultHandler   answers requests that are not let through
     */
    public PermissionInterceptor(Enforcer enforcer, HandlerPermissions permissions, ClaimsExtractor claimsExtractor,
            EnforcementResultHandler resultHandler) {
        this.enforcer = enforcer;
        this.permissions = permissions;
        this.claimsExtractor = claimsExtractor;
        this.resultHandler = resultHandler;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // An error page reports on a request that was handled already
        if (!(handler instanceof HandlerMethod method) || request.getDispatcherType() == DispatcherType.ERROR
                || OPTIONS_HANDLER.equals(method.getBeanType().getName())) {
            return true;
        }
        // The second dispatch of an asynchronous request to the handler it was let through to. A
        // filter may start the asynchronous request before any dispatch reached a handler, so the
        // dispatcher type alone does not show that the request was decided
        String routePattern = routePattern(request);
        Passed passed = new Passed(method.getBeanType(), method.getMethod(), routePattern, Map.copyOf(pathVariables(request)));
        if (request.getDispatcherType() == DispatcherType.ASYNC && passed.equals(request.getAttribute(PASSED))) {
            return true;
        }
        HandlerPermission permission = permissions.get(method);
        if (!permission.enforces(routePattern)) {
            request.setAttribute(PASSED, passed);
            return true;
        }
        EnforcementResult result = enforce(request, permission, routePattern);
        if (result.isAllowed()) {
            request.setAttribute(PASSED, passed);
            return true;
        }
        resultHandler.handleDenied(request, response, result);
        // A handler that neither threw nor sent an error must not let the request through
        if (!response.isCommitted() && response.getStatus() < 400) {
            response.sendError(result.getStatus().orElse(EnforcementResult.FORBIDDEN));
        }
        return false;
    }

    private EnforcementResult enforce(HttpServletRequest request, HandlerPermission permission, String routePattern) {
        if (routePattern == null) {
            return refused("No route pattern is known for a request to " + permission.getHandler());
        }
        Map<String, String> pathVariables = pathVariables(request);
        // Exceptions are named by their type only, also at debug level: their messages may quote
        // claim values or values of the request
        Optional<Map<String, Object>> claims;
        try {
            claims = claimsExtractor.extract(request);
        } catch (RuntimeException e) {
            return refused("Extracting the caller's claims failed with " + e.getClass().getName());
        }
        PermissionSpec spec;
        try {
            spec = permission.spec(Expressions.context(pathVariables, new RequestView(request),
                    claims.map(Collections::unmodifiableMap).orElse(null)));
        } catch (HandlerPermission.ExpressionFailure e) {
            return refused(e.getMessage());
        }
        RequestDescription.Builder description = RequestDescription.builder()
                .method(request.getMethod())
                .routePattern(routePattern)
                .pathVariables(pathVariables);
        claims.ifPresent(description::claims);
        return enforcer.enforce(description.build(), spec);
    }

    private EnforcementResult refused(String reason) {
        if (warned.size() < MAX_WARNED && warned.add(reason)) {
            LOGGER.log(Level.WARNING, "Denied without asking the PDP: " + reason
                    + " (logged once; later occurrences at debug level)");
        } else {
            LOGGER.log(Level.DEBUG, () -> "Denied without asking the PDP: " + reason);
        }
        return EnforcementResult.refused(reason);
    }

    private static String routePattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof PathPattern pathPattern) {
            return pathPattern.getPatternString();
        }
        return pattern instanceof String string ? string : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    // The path variables too: an application may dispatch asynchronously to another URI of the route
    private record Passed(Class<?> type, Method method, String routePattern, Map<String, String> pathVariables) {
    }

    @Override
    public String toString() {
        return "PermissionInterceptor{enforcer=" + enforcer + ", claimsExtractor=" + claimsExtractor
                + ", resultHandler=" + resultHandler + "}";
    }
}
