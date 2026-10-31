package io.github.authzenenforcer.core.model;

import io.github.authzenenforcer.core.internal.json.JsonValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The operation the subject wants to perform on the resource.
 *
 * <p>Serialized as the AuthZEN {@code action} object:
 * <pre>{@code
 * {"name": "extend-loan", "properties": {"period": "2W"}}
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class Action {

    private final String name;
    private final Map<String, Object> properties;

    private Action(Builder builder) {
        this.name = Checks.requireText(builder.name, "name");
        this.properties = JsonValues.copyObject(builder.properties);
    }

    /**
     * Creates an action without properties.
     *
     * @param name the action name, such as {@code read}
     * @return the action
     * @throws IllegalStateException    if the name is {@code null}
     * @throws IllegalArgumentException if the name is empty
     */
    public static Action of(String name) {
        return builder().name(name).build();
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
     * Returns the action name.
     *
     * @return the name, never empty
     */
    public String getName() {
        return name;
    }

    /**
     * Returns additional attributes of the action.
     *
     * @return an unmodifiable map, empty if there are no properties
     */
    public Map<String, Object> getProperties() {
        return properties;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Action other)) {
            return false;
        }
        return name.equals(other.name) && properties.equals(other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, properties);
    }

    @Override
    public String toString() {
        return "Action{name=" + name + ", properties=" + properties + "}";
    }

    /**
     * Builder for {@link Action}. Not thread-safe.
     */
    public static final class Builder {

        private String name;
        private final Map<String, Object> properties = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the action name. Required.
         *
         * @param name the name, such as {@code read}
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Adds a property, replacing any previous value with the same name.
         *
         * @param name  the property name
         * @param value a JSON-compatible value, may be {@code null}
         * @return this builder
         */
        public Builder property(String name, Object value) {
            properties.put(Objects.requireNonNull(name, "name"), value);
            return this;
        }

        /**
         * Replaces all properties.
         *
         * @param properties the properties, with JSON-compatible values
         * @return this builder
         */
        public Builder properties(Map<String, ?> properties) {
            Objects.requireNonNull(properties, "properties");
            this.properties.clear();
            this.properties.putAll(properties);
            return this;
        }

        /**
         * Builds the action. Properties are deep-copied.
         *
         * @return the action
         * @throws IllegalStateException    if the name is not set
         * @throws IllegalArgumentException if the name is empty, or a property value is not
         *                                  JSON-compatible
         */
        public Action build() {
            return new Action(this);
        }
    }
}
