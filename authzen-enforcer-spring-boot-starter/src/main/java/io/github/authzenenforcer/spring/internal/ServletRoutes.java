package io.github.authzenenforcer.spring.internal;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the routes of the Spring MVC handler mappings.
 */
public final class ServletRoutes {

    private ServletRoutes() {
    }

    /**
     * Returns the routes of every request mapping handler mapping of a bean factory.
     *
     * @param beans the bean factory
     * @return the routes
     */
    public static List<ProtectedRoutesCheck.Route> of(ListableBeanFactory beans) {
        List<ProtectedRoutesCheck.Route> routes = new ArrayList<>();
        for (RequestMappingInfoHandlerMapping mapping : beans.getBeansOfType(RequestMappingInfoHandlerMapping.class)
                .values()) {
            mapping.getHandlerMethods().forEach((info, handler) -> routes.add(new ProtectedRoutesCheck.Route(handler,
                    info.getMethodsCondition().getMethods().stream().map(RequestMethod::name).toList(),
                    info.getPatternValues())));
        }
        return routes;
    }
}
