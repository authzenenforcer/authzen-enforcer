package io.github.authzenenforcer.core.enforce;

import io.github.authzenenforcer.core.internal.json.JsonValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The explicit values of a permission check, independent of how an endpoint declares them, for
 * example through an annotation. Every value is optional; what is missing is inferred.
 *
 * <p>Context members are added to the evaluation request as they are; the PDP sees them in
 * {@code context}.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class PermissionSpec {

    private static final PermissionSpec NONE = new Builder().build();

    private final String action;
    private final String resourceType;
    private final String resourceId;
    private final String subjectType;
    private final Map<String, Object> context;

    private PermissionSpec(Builder builder) {
        this.action = builder.action;
        this.resourceType = builder.resourceType;
        this.resourceId = builder.resourceId;
        this.subjectType = builder.subjectType;
        this.context = JsonValues.copyObject(builder.context);
    }

    /**
     * Returns a spec without explicit values, so that everything is inferred.
     *
     * @return the empty spec
     */
    public static PermissionSpec none() {
        return NONE;
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
     * Returns the explicit action name.
     *
     * @return the action name, or empty to infer it
     */
    public Optional<String> getAction() {
        return Optional.ofNullable(action);
    }

    /**
     * Returns the explicit resource type.
     *
     * @return the resource type, or empty to infer it
     */
    public Optional<String> getResourceType() {
        return Optional.ofNullable(resourceType);
    }

    /**
     * Returns the explicit resource id.
     *
     * @return the resource id, or empty to infer it
     */
    public Optional<String> getResourceId() {
        return Optional.ofNullable(resourceId);
    }

    /**
     * Returns the explicit subject type.
     *
     * @return the subject type, or empty to infer it
     */
    public Optional<String> getSubjectType() {
        return Optional.ofNullable(subjectType);
    }

    /**
     * Returns the context members to send.
     *
     * @return an unmodifiable, insertion-ordered map, empty if there are none
     */
    public Map<String, Object> getContext() {
        return context;
    }

    /**
     * Returns whether the spec has no explicit values.
     *
     * @return {@code true} if everything is inferred
     */
    public boolean isEmpty() {
        return action == null && resourceType == null && resourceId == null && subjectType == null
                && context.isEmpty();
    }

    /**
     * Returns a spec with the values of this spec, and those of {@code lower} where this spec has
     * none. Context members of this spec replace those of {@code lower} with the same name.
     *
     * @param lower the spec with lower precedence
     * @return the combined spec
     */
    public PermissionSpec orElse(PermissionSpec lower) {
        Objects.requireNonNull(lower, "lower must not be null");
        if (lower.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return lower;
        }
        Builder combined = new Builder();
        combined.action = action != null ? action : lower.action;
        combined.resourceType = resourceType != null ? resourceType : lower.resourceType;
        combined.resourceId = resourceId != null ? resourceId : lower.resourceId;
        combined.subjectType = subjectType != null ? subjectType : lower.subjectType;
        combined.context.putAll(lower.context);
        combined.context.putAll(context);
        return combined.build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof PermissionSpec other
                && Objects.equals(action, other.action)
                && Objects.equals(resourceType, other.resourceType)
                && Objects.equals(resourceId, other.resourceId)
                && Objects.equals(subjectType, other.subjectType)
                && context.equals(other.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, resourceType, resourceId, subjectType, context);
    }

    /**
     * Returns a description for logging. Context values are masked; names are shown.
     */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("PermissionSpec{");
        if (action != null) {
            out.append("action=").append(action).append(", ");
        }
        if (resourceType != null) {
            out.append("resourceType=").append(resourceType).append(", ");
        }
        if (resourceId != null) {
            out.append("resourceId=").append(resourceId).append(", ");
        }
        if (subjectType != null) {
            out.append("subjectType=").append(subjectType).append(", ");
        }
        out.append("context=").append(context.keySet()).append('}');
        return out.toString();
    }

    /**
     * Builder for {@link PermissionSpec}. Not thread-safe.
     */
    public static final class Builder {

        private String action;
        private String resourceType;
        private String resourceId;
        private String subjectType;
        private final Map<String, Object> context = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the action name.
         *
         * @param action the action name
         * @return this builder
         */
        public Builder action(String action) {
            this.action = Objects.requireNonNull(action, "action must not be null");
            return this;
        }

        /**
         * Sets the resource type.
         *
         * @param resourceType the resource type
         * @return this builder
         */
        public Builder resourceType(String resourceType) {
            this.resourceType = Objects.requireNonNull(resourceType, "resourceType must not be null");
            return this;
        }

        /**
         * Sets the resource id.
         *
         * @param resourceId the resource id
         * @return this builder
         */
        public Builder resourceId(String resourceId) {
            this.resourceId = Objects.requireNonNull(resourceId, "resourceId must not be null");
            return this;
        }

        /**
         * Sets the subject type.
         *
         * @param subjectType the subject type
         * @return this builder
         */
        public Builder subjectType(String subjectType) {
            this.subjectType = Objects.requireNonNull(subjectType, "subjectType must not be null");
            return this;
        }

        /**
         * Adds a context member, replacing any previous value with the same name.
         *
         * @param name  the member name
         * @param value a JSON-compatible value, may be {@code null}
         * @return this builder
         */
        public Builder context(String name, Object value) {
            context.put(Objects.requireNonNull(name, "name must not be null"), value);
            return this;
        }

        /**
         * Builds the spec. Context values are deep-copied.
         *
         * @return the spec
         * @throws IllegalArgumentException if a context value is not JSON-compatible
         */
        public PermissionSpec build() {
            return new PermissionSpec(this);
        }
    }
}
