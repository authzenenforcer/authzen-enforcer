package io.github.authzenenforcer.core.model;

import io.github.authzenenforcer.core.internal.json.JsonValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A free-form JSON object with additional information.
 *
 * <p>In a request it describes the environment, such as the time or the client IP address. In a
 * response the PDP may use it to explain the decision or to give advice to the PEP.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class Context {

    private static final Context EMPTY = new Context(Map.of());

    private final Map<String, Object> values;

    private Context(Map<String, ?> values) {
        this.values = JsonValues.copyObject(values);
    }

    /**
     * Returns an empty context.
     *
     * @return the empty context
     */
    public static Context empty() {
        return EMPTY;
    }

    /**
     * Creates a context from a map. The map is deep-copied.
     *
     * @param values the context members, with JSON-compatible values
     * @return the context
     * @throws IllegalArgumentException if a value is not JSON-compatible
     */
    public static Context of(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        return values.isEmpty() ? EMPTY : new Context(values);
    }

    /**
     * Returns a new builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the context members.
     *
     * @return an unmodifiable, insertion-ordered map
     */
    public Map<String, Object> asMap() {
        return values;
    }

    /**
     * Returns whether the context has no members.
     *
     * @return {@code true} if the context is empty
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Context other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    /**
     * Returns a description for logging. Values are masked, since they may hold personal data such
     * as an IP address; member names are shown.
     */
    @Override
    public String toString() {
        return "Context" + Redaction.maskValues(values);
    }

    /**
     * Builder for {@link Context}. Not thread-safe.
     */
    public static final class Builder {

        private final Map<String, Object> values = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Adds a member, replacing any previous value with the same name.
         *
         * @param name  the member name
         * @param value a JSON-compatible value, may be {@code null}
         * @return this builder
         */
        public Builder put(String name, Object value) {
            values.put(Objects.requireNonNull(name, "name"), value);
            return this;
        }

        /**
         * Builds the context. Values are deep-copied.
         *
         * @return the context
         * @throws IllegalArgumentException if a value is not JSON-compatible
         */
        public Context build() {
            return Context.of(values);
        }
    }
}
