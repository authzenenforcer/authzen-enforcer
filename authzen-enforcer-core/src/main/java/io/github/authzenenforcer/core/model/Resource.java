package io.github.authzenenforcer.core.model;

import io.github.authzenenforcer.core.internal.json.JsonValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The object an access request is about, such as a document or an account.
 *
 * <p>Serialized as the AuthZEN {@code resource} object:
 * <pre>{@code
 * {"type": "book", "id": "123", "properties": {"library_record": {"title": "AuthZEN in Action"}}}
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class Resource {

    private final String type;
    private final String id;
    private final Map<String, Object> properties;

    private Resource(Builder builder) {
        this.type = Checks.requireSet(builder.type, "type");
        this.id = Checks.requireSet(builder.id, "id");
        this.properties = JsonValues.copyObject(builder.properties);
    }

    /**
     * Creates a resource without properties.
     *
     * @param type the resource type, such as {@code document}
     * @param id   the identifier of the resource, unique within its type
     * @return the resource
     * @throws IllegalStateException if an argument is {@code null}
     */
    public static Resource of(String type, String id) {
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
     * Returns the resource type.
     *
     * @return the type
     */
    public String getType() {
        return type;
    }

    /**
     * Returns the identifier of the resource, unique within its type.
     *
     * @return the identifier
     */
    public String getId() {
        return id;
    }

    /**
     * Returns additional attributes of the resource.
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
        if (!(o instanceof Resource other)) {
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
        return "Resource{type=" + type + ", id=" + id + ", properties=" + properties + "}";
    }

    /**
     * Builder for {@link Resource}. Not thread-safe.
     */
    public static final class Builder {

        private String type;
        private String id;
        private final Map<String, Object> properties = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the resource type. Required.
         *
         * @param type the type, such as {@code document}
         * @return this builder
         */
        public Builder type(String type) {
            this.type = type;
            return this;
        }

        /**
         * Sets the identifier of the resource. Required.
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
         * Builds the resource. Properties are deep-copied.
         *
         * @return the resource
         * @throws IllegalStateException    if the type or id is not set
         * @throws IllegalArgumentException if a property value is not JSON-compatible
         */
        public Resource build() {
            return new Resource(this);
        }
    }
}
