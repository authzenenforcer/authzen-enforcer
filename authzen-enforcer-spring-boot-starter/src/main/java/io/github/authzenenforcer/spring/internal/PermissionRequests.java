package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.EnforcementResult;
import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.core.enforce.PermissionSpec;
import io.github.authzenenforcer.core.enforce.RequestDescription;

import java.lang.System.Logger.Level;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a request to an enforced handler into what the {@link Enforcer} is asked, the same way for
 * Spring MVC and Spring WebFlux, and refuses requests that cannot be asked about.
 */
final class PermissionRequests {

    // Messages name handlers and exception types, never request values, so there are only as many
    // as there are handlers; the cap only guards against a custom claims extractor
    private static final int MAX_WARNED = 1000;

    private final System.Logger logger;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * Creates the requests.
     *
     * @param logger the logger of refusals
     */
    PermissionRequests(System.Logger logger) {
        this.logger = logger;
    }

    /**
     * Refuses a request without asking the PDP, with a warning the first time for each reason.
     * Reasons name handlers and exception types, never values of the request.
     *
     * @param reason the reason
     * @return the result
     */
    EnforcementResult refused(String reason) {
        if (warned.size() < MAX_WARNED && warned.add(reason)) {
            logger.log(Level.WARNING, "Denied without asking the PDP: " + reason
                    + " (logged once; later occurrences at debug level)");
        } else {
            logger.log(Level.DEBUG, () -> "Denied without asking the PDP: " + reason);
        }
        return EnforcementResult.refused(reason);
    }

    /**
     * Refuses a request to a handler whose route pattern is unknown.
     *
     * @param permission the handler's permission
     * @return the result
     */
    EnforcementResult noRoutePattern(HandlerPermission permission) {
        return refused("No route pattern is known for a request to " + permission.getHandler());
    }

    /**
     * Refuses a request whose caller's claims could not be extracted. Only the type of the failure is
     * named, also at debug level: its message may quote claim values or values of the request.
     *
     * @param failure the failure
     * @return the result
     */
    EnforcementResult claimsFailed(Throwable failure) {
        return refused("Extracting the caller's claims failed with " + failure.getClass().getName());
    }

    /**
     * Evaluates the handler's expressions and describes the request.
     *
     * @param permission    the handler's permission
     * @param method        the HTTP method
     * @param routePattern  the route pattern
     * @param pathVariables the path variables
     * @param view          the request as expressions see it
     * @param claims        the caller's claims, or empty for an anonymous caller
     * @return what to ask, or the refusal if an expression failed
     */
    Prepared prepare(HandlerPermission permission, String method, String routePattern,
            Map<String, String> pathVariables, RequestView view, Optional<Map<String, Object>> claims) {
        PermissionSpec spec;
        try {
            spec = permission.spec(Expressions.context(pathVariables, view,
                    claims.map(Collections::unmodifiableMap).orElse(null)));
        } catch (HandlerPermission.ExpressionFailure e) {
            return new Prepared(null, null, refused(e.getMessage()));
        }
        RequestDescription.Builder description = RequestDescription.builder()
                .method(method)
                .routePattern(routePattern)
                .pathVariables(pathVariables);
        claims.ifPresent(description::claims);
        return new Prepared(description.build(), spec, null);
    }

    /**
     * What to ask the {@link Enforcer}, or the result of refusing the request without asking.
     *
     * @param description the request, if it is asked about
     * @param spec        the explicit values, if it is asked about
     * @param refusal     the result, if it is refused
     */
    record Prepared(RequestDescription description, PermissionSpec spec, EnforcementResult refusal) {
    }
}
