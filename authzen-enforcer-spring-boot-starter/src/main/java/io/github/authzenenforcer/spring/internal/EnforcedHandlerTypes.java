package io.github.authzenenforcer.spring.internal;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Tells which handler methods a web integration enforces.
 */
@FunctionalInterface
public interface EnforcedHandlerTypes {

    /**
     * Returns the handler methods.
     *
     * @return the handler methods, by the user class of their bean
     */
    Set<Handler> handlers();

    /**
     * A handler method of a bean.
     *
     * @param type   the user class of the bean
     * @param method the method
     */
    record Handler(Class<?> type, Method method) {
    }
}
