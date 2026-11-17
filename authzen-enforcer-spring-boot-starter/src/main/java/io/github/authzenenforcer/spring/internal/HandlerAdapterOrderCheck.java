package io.github.authzenenforcer.spring.internal;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.reactive.HandlerAdapter;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Warns at startup about a handler adapter of the application that Spring WebFlux's dispatcher
 * picks before {@link PermissionHandlerAdapter} for handler methods: the handler methods it runs
 * are not enforced.
 */
public final class HandlerAdapterOrderCheck implements SmartInitializingSingleton {

    private static final System.Logger LOGGER = System.getLogger(HandlerAdapterOrderCheck.class.getName());

    private final ListableBeanFactory beans;
    private final Supplier<? extends List<ProtectedRoutesCheck.Route>> routes;

    /**
     * Creates the check.
     *
     * @param beans  the bean factory that holds the handler adapters
     * @param routes the routes of the handler mappings, read once the context is ready
     */
    public HandlerAdapterOrderCheck(ListableBeanFactory beans,
            Supplier<? extends List<ProtectedRoutesCheck.Route>> routes) {
        this.beans = beans;
        this.routes = routes;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // Collected and sorted as the dispatcher does
        List<HandlerAdapter> adapters = new ArrayList<>(BeanFactoryUtils
                .beansOfTypeIncludingAncestors(beans, HandlerAdapter.class, true, false).values());
        AnnotationAwareOrderComparator.sort(adapters);
        for (ProtectedRoutesCheck.Route route : routes.get()) {
            for (HandlerAdapter adapter : adapters) {
                if (adapter instanceof PermissionHandlerAdapter) {
                    break;
                }
                if (adapter.supports(route.handler())) {
                    LOGGER.log(Level.WARNING, "The handler adapter " + adapter.getClass().getName() + " runs "
                            + route.handler() + " before enforcement can decide about it: order it after"
                            + " PermissionHandlerAdapter, whose order is Ordered.HIGHEST_PRECEDENCE");
                    return;
                }
            }
        }
    }
}
