package io.github.authzenenforcer.core.model;

import java.util.Map;
import java.util.StringJoiner;

/**
 * Masks values that may hold personal data in {@code toString()} output, so that model objects can
 * be logged safely.
 */
final class Redaction {

    /** Replacement for a masked value. */
    static final String MASK = "***";

    private Redaction() {
    }

    /**
     * Renders a JSON object with its member names and every value masked.
     */
    static String maskValues(Map<String, ?> object) {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        for (String name : object.keySet()) {
            joiner.add(name + "=" + MASK);
        }
        return joiner.toString();
    }
}
