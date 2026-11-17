package io.github.authzenenforcer.spring.internal;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.reactive.HandlerAdapter;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
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
        List<HandlerAdapter> before = adapters.subList(0, Math.max(0, indexOfEnforcement(adapters)));
        Set<HandlerAdapter> warned = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ProtectedRoutesCheck.Route route : routes.get()) {
            for (HandlerAdapter adapter : before) {
                if (warned.contains(adapter)) {
                    continue;
                }
                // The check only warns: an adapter that fails here is reported, not allowed to fail the startup
                try {
                    if (adapter.supports(route.handler())) {
                        warned.add(adapter);
                        LOGGER.log(Level.WARNING, "The handler adapter " + adapter.getClass().getName() + " runs "
                                + route.handler() + " before enforcement can decide about it: order it after"
                                + " PermissionHandlerAdapter, whose order is Ordered.HIGHEST_PRECEDENCE");
                    }
                } catch (RuntimeException e) {
                    warned.add(adapter);
                    LOGGER.log(Level.WARNING, "The handler adapter " + adapter.getClass().getName() + " failed with "
                            + e.getClass().getName() + " when asked whether it runs " + route.handler()
                            + "; if it does, it runs before enforcement can decide");
                }
            }
        }
    }

    private static int indexOfEnforcement(List<HandlerAdapter> adapters) {
        for (int i = 0; i < adapters.size(); i++) {
            if (adapters.get(i) instanceof PermissionHandlerAdapter) {
                return i;
            }
        }
        return 0;
    }
}
