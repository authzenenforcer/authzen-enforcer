package io.github.authzenenforcer.core.model;

/** Validation helpers shared by the builders. */
final class Checks {

    private Checks() {
    }

    /**
     * Checks a required object field when a builder builds its object.
     *
     * @throws IllegalStateException if the value was not set
     */
    static <T> T requireSet(T value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
