package io.github.authzenenforcer.core.model;

import io.github.authzenenforcer.core.internal.json.JsonValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The user or machine principal an access request is made for.
 *
 * <p>Serialized as the AuthZEN {@code subject} object:
 * <pre>{@code
 * {"type": "user", "id": "alice@example.com", "properties": {"department": "Sales"}}
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class Subject {

    private final String type;
    private final String id;
    private final Map<String, Object> properties;

    private Subject(Builder builder) {
        this.type = Checks.requireText(builder.type, "type");
        this.id = Checks.requireText(builder.id, "id");
        this.properties = JsonValues.copyObject(builder.properties);
    }

    /**
     * Creates a subject without properties.
     *
     * @param type the subject type, such as {@code user}
     * @param id   the identifier of the subject, unique within its type
     * @return the subject
     * @throws IllegalStateException    if an argument is {@code null}
     * @throws IllegalArgumentException if an argument is empty
     */
    public static Subject of(String type, String id) {
        return builder().type(type).id(id).build();
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
     * Returns the subject type.
     *
     * @return the type, never empty
     */
    public String getType() {
        return type;
    }

    /**
     * Returns the identifier of the subject, unique within its type.
     *
     * @return the identifier, never empty
     */
    public String getId() {
        return id;
    }

    /**
     * Returns additional attributes of the subject.
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
        if (!(o instanceof Subject other)) {
            return false;
        }
        return type.equals(other.type) && id.equals(other.id) && properties.equals(other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, id, properties);
    }

    @Override
    public String toString() {
        return "Subject{type=" + type + ", id=" + id + ", properties=" + properties + "}";
    }

    /**
     * Builder for {@link Subject}. Not thread-safe.
     */
    public static final class Builder {

        private String type;
        private String id;
        private final Map<String, Object> properties = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the subject type. Required.
         *
         * @param type the type, such as {@code user}
         * @return this builder
         */
        public Builder type(String type) {
            this.type = type;
            return this;
        }

        /**
         * Sets the identifier of the subject. Required.
         *
         * @param id the identifier, unique within the type
         * @return this builder
         */
        public Builder id(String id) {
            this.id = id;
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
         * Builds the subject. Properties are deep-copied.
         *
         * @return the subject
         * @throws IllegalStateException    if the type or id is not set
         * @throws IllegalArgumentException if the type or id is empty, or a property value is not
         *                                  JSON-compatible
         */
        public Subject build() {
            return new Subject(this);
        }
    }
}
