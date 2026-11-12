package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.core.enforce.InferenceException;
import io.github.authzenenforcer.core.enforce.RequestDescription;
import io.github.authzenenforcer.core.enforce.ResourceInferrer;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.util.UriTemplate;

import java.lang.System.Logger.Level;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks every enforced route once the application context is ready: it fails the startup for
 * invalid annotations, and warns about routes whose requests would be refused.
 */
public final class ProtectedRoutesCheck implements SmartInitializingSingleton, EnforcedHandlerTypes {

    private static final System.Logger LOGGER = System.getLogger(ProtectedRoutesCheck.class.getName());

    // The methods a mapping without a method condition is checked for; OPTIONS is never enforced
    private static final List<String> ALL_METHODS = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");

    private final ListableBeanFactory beans;
    private final Enforcer enforcer;
    private final HandlerPermissions permissions;
    private final ResourceInferrer resourceInferrer;

    /**
     * Creates the check.
     *
     * @param beans       the bean factory that holds the handler mappings
     * @param enforcer    the enforcer
     * @param permissions      how handlers are enforced
     * @param resourceInferrer tells which routes are collections, or {@code null} not to check that
     */
    public ProtectedRoutesCheck(ListableBeanFactory beans, Enforcer enforcer, HandlerPermissions permissions,
            ResourceInferrer resourceInferrer) {
        this.beans = beans;
        this.enforcer = enforcer;
        this.permissions = permissions;
        this.resourceInferrer = resourceInferrer;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int routes = 0;
        for (RequestMappingInfoHandlerMapping mapping : beans.getBeansOfType(RequestMappingInfoHandlerMapping.class)
                .values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                routes += check(entry.getKey(), entry.getValue());
            }
        }
        int checked = routes;
        LOGGER.log(Level.DEBUG, () -> "Checked " + checked + " enforced routes");
    }

    // Explicit values that make a collection look like a single resource, or let callers name it
    private void checkCollection(HandlerPermission permission, String pattern, List<String> methods) {
        boolean type = permission.setsResourceType();
        boolean idFromRequest = permission.takesResourceIdFromRequest();
        if ((!type && !idFromRequest) || methods.stream().noneMatch(method -> isCollection(method, pattern))) {
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
        for (RequestMappingInfoHandlerMapping mapping : beans.getBeansOfType(RequestMappingInfoHandlerMapping.class)
                .values()) {
            mapping.getHandlerMethods().values()
                    .forEach(handler -> handlers.add(new Handler(handler.getBeanType(), handler.getMethod())));
        }
        return handlers;
    }

    private int check(RequestMappingInfo info, HandlerMethod handler) {
        HandlerPermission permission = permissions.get(handler);
        Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
        List<String> methods = declared.isEmpty()
                ? ALL_METHODS
                : declared.stream().map(RequestMethod::name).toList();
        int routes = 0;
        for (String pattern : info.getPatternValues()) {
            if (!permission.enforces(pattern)) {
                continue;
            }
            routes++;
            for (String method : methods) {
                enforcer.check(method, pattern, permission.staticSpec()).ifPresent(reason -> LOGGER.log(Level.WARNING,
                        "Requests to " + permission.getHandler() + " will be denied: " + reason));
            }
            checkCollection(permission, pattern, methods);
        }
        return routes;
    }
}
