package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.core.enforce.InferenceException;
import io.github.authzenenforcer.core.enforce.RequestDescription;
import io.github.authzenenforcer.core.enforce.ResourceInferrer;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.UriTemplate;

import java.lang.System.Logger.Level;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Checks every enforced route once the application context is ready: it fails the startup for
 * invalid annotations, and warns about routes whose requests would be refused.
 */
public final class ProtectedRoutesCheck implements SmartInitializingSingleton, EnforcedHandlerTypes {

    private static final System.Logger LOGGER = System.getLogger(ProtectedRoutesCheck.class.getName());

    // The methods a mapping without a method condition is checked for; OPTIONS is never enforced
    private static final List<String> ALL_METHODS = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");

    private final Supplier<? extends Collection<Route>> routes;
    private final Enforcer enforcer;
    private final HandlerPermissions permissions;
    private final ResourceInferrer resourceInferrer;

    /**
     * Creates the check.
     *
     * @param routes           the routes of the handler mappings, read once the context is ready
     * @param enforcer         the enforcer
     * @param permissions      how handlers are enforced
     * @param resourceInferrer tells which routes are collections, or {@code null} not to check that
     */
    public ProtectedRoutesCheck(Supplier<? extends Collection<Route>> routes, Enforcer enforcer,
            HandlerPermissions permissions, ResourceInferrer resourceInferrer) {
        this.routes = routes;
        this.enforcer = enforcer;
        this.permissions = permissions;
        this.resourceInferrer = resourceInferrer;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int enforced = 0;
        for (Route route : routes.get()) {
            enforced += check(route);
        }
        int checked = enforced;
        LOGGER.log(Level.DEBUG, () -> "Checked " + checked + " enforced routes");
    }

    // Explicit values that make a collection look like a single resource, or let callers name it.
    // A type with an id from the request and an explicit action names a single resource, as
    // GET /documents/{id}/download does with action = "read", resourceType = "document" and
    // resourceId = "#id". Without the action, the action of a collection, such as list, is inferred
    private void checkCollection(HandlerPermission permission, String pattern, List<String> methods) {
        boolean type = permission.setsResourceType();
        boolean idFromRequest = permission.takesResourceIdFromRequest();
        boolean namesSingleResource = type && idFromRequest && permission.setsAction();
        if ((!type && !idFromRequest) || namesSingleResource
                || methods.stream().noneMatch(method -> isCollection(method, pattern))) {
            return;
        }
        if (type) {
            LOGGER.log(Level.WARNING, permission.getHandler() + " sets the type of the collection " + pattern
                    + ": the PDP is asked about {type: <that type>, id: <collection>}, as about a single resource"
                    + " of that type with that id. Give collections a type that no single resource has");
        }
        if (idFromRequest) {
            LOGGER.log(Level.WARNING, permission.getHandler() + " takes the id of the collection " + pattern
                    + " from the request, so callers choose which collection the PDP is asked about."
                    + " Use a literal id, such as resourceId = \"'documents'\"");
        }
    }

    private boolean isCollection(String method, String pattern) {
        if (resourceInferrer == null) {
            return false;
        }
        RequestDescription.Builder request = RequestDescription.builder().method(method).routePattern(pattern);
        // Any valid value: only the shape of the route matters
        new UriTemplate(pattern).getVariableNames().forEach(name -> request.pathVariable(name, "x"));
        try {
            return resourceInferrer.infer(request.build(), false).isCollection();
        } catch (InferenceException e) {
            // Reported by the check of the route already
            return false;
        }
    }

    @Override
    public Set<Handler> handlers() {
        Set<Handler> handlers = new HashSet<>();
        for (Route route : routes.get()) {
            handlers.add(new Handler(route.handler().getBeanType(), route.handler().getMethod()));
        }
        return handlers;
    }

    private int check(Route route) {
        HandlerPermission permission = permissions.get(route.handler());
        List<String> methods = route.methods().isEmpty() ? ALL_METHODS : route.methods();
        int enforced = 0;
        for (String pattern : route.patterns()) {
            if (!permission.enforces(pattern)) {
                continue;
            }
            enforced++;
            for (String method : methods) {
                enforcer.check(method, pattern, permission.staticSpec()).ifPresent(reason -> LOGGER.log(Level.WARNING,
                        "Requests to " + permission.getHandler() + " will be denied: " + reason));
            }
            checkCollection(permission, pattern, methods);
        }
        return enforced;
    }

    /**
     * A request mapping of a handler method.
     *
     * @param handler  the handler method
     * @param methods  the HTTP methods it is mapped to, empty for every method
     * @param patterns its route patterns
     */
    public record Route(HandlerMethod handler, List<String> methods, Collection<String> patterns) {
    }
}
