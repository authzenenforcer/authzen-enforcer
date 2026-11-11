package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.Enforcer;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

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

    /**
     * Creates the check.
     *
     * @param beans       the bean factory that holds the handler mappings
     * @param enforcer    the enforcer
     * @param permissions how handlers are enforced
     */
    public ProtectedRoutesCheck(ListableBeanFactory beans, Enforcer enforcer, HandlerPermissions permissions) {
        this.beans = beans;
        this.enforcer = enforcer;
        this.permissions = permissions;
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
        }
        return routes;
    }
}
