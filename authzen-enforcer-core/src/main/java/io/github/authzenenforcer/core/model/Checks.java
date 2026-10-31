package io.github.authzenenforcer.core.model;

/** Validation helpers shared by the builders. */
final class Checks {

    private Checks() {
    }

    /**
     * Checks a required string field when a builder builds its object.
     *
     * @throws IllegalStateException    if the value was not set
     * @throws IllegalArgumentException if the value is empty
     */
    static String requireText(String value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " is required");
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
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
