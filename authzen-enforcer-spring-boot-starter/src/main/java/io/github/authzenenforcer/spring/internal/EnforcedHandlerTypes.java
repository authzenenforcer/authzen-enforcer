package io.github.authzenenforcer.spring.internal;

import java.util.Set;

/**
 * Tells which bean types have handler methods that a web integration enforces.
 */
@FunctionalInterface
public interface EnforcedHandlerTypes {

    /**
     * Returns the types.
     *
     * @return the user classes of the beans with enforced handler methods
     */
    Set<Class<?>> types();
}
