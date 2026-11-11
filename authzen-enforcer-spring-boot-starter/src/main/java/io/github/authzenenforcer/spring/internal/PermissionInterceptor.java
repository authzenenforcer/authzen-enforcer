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

import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.lang.System.Logger.Level;
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

    // The handler method a request was let through to; request attributes cannot be set by the caller
    private static final String PASSED = PermissionInterceptor.class.getName() + ".PASSED";

    private final Enforcer enforcer;
    private final HandlerPermissions permissions;
    private final ClaimsExtractor claimsExtractor;
    private final EnforcementResultHandler resultHandler;
    private final CurrentAuthentication authentication;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * Creates the interceptor.
     *
     * @param enforcer        the enforcer
     * @param permissions     how handlers are enforced
     * @param claimsExtractor gets the caller's claims
     * @param resultHandler   answers requests that are not let through
     * @param authentication  supplies the {@code #authentication} of expressions, or {@code null}
     *                        without Spring Security
     */
    public PermissionInterceptor(Enforcer enforcer, HandlerPermissions permissions, ClaimsExtractor claimsExtractor,
            EnforcementResultHandler resultHandler, CurrentAuthentication authentication) {
        this.enforcer = enforcer;
        this.permissions = permissions;
        this.claimsExtractor = claimsExtractor;
        this.resultHandler = resultHandler;
        this.authentication = authentication;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // An error page reports on a request that was handled already
        if (!(handler instanceof HandlerMethod method) || request.getDispatcherType() == DispatcherType.ERROR) {
            return true;
        }
        // The second dispatch of an asynchronous request to the handler it was let through to. A
        // filter may start the asynchronous request before any dispatch reached a handler, so the
        // dispatcher type alone does not show that the request was decided
        if (request.getDispatcherType() == DispatcherType.ASYNC
                && method.getMethod().equals(request.getAttribute(PASSED))) {
            return true;
        }
        HandlerPermission permission = permissions.get(method);
        String routePattern = routePattern(request);
        if (!permission.enforces(routePattern)) {
            request.setAttribute(PASSED, method.getMethod());
            return true;
        }
        EnforcementResult result = enforce(request, permission, routePattern);
        if (result.isAllowed()) {
            request.setAttribute(PASSED, method.getMethod());
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
        PermissionSpec spec;
        try {
            spec = permission.spec(variables(request, pathVariables));
        } catch (HandlerPermission.ExpressionFailure e) {
            LOGGER.log(Level.DEBUG, "Expression failed", e.getCause());
            return refused(e.getMessage());
        }
        Optional<Map<String, Object>> claims;
        try {
            claims = claimsExtractor.extract(request);
        } catch (RuntimeException e) {
            // Its message may hold claim values, so only the type is named
            LOGGER.log(Level.DEBUG, "Extracting the claims failed", e);
            return refused("Extracting the caller's claims failed with " + e.getClass().getName());
        }
        RequestDescription.Builder description = RequestDescription.builder()
                .method(request.getMethod())
                .routePattern(routePattern)
                .pathVariables(pathVariables);
        claims.ifPresent(description::claims);
        return enforcer.enforce(description.build(), spec);
    }

    private SimpleEvaluationContext variables(HttpServletRequest request, Map<String, String> pathVariables) {
        // Read-only: no types, constructors, bean references or assignments
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding()
                .withInstanceMethods()
                .build();
        pathVariables.forEach(context::setVariable);
        // Set last, so that a path variable cannot stand in for them
        context.setVariable("request", request);
        context.setVariable("authentication", authentication != null ? authentication.get() : null);
        return context;
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

    @Override
    public String toString() {
        return "PermissionInterceptor{enforcer=" + enforcer + ", claimsExtractor=" + claimsExtractor
                + ", resultHandler=" + resultHandler + "}";
    }
}
