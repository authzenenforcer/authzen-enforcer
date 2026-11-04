package io.github.authzenenforcer.core.model;

import io.github.authzenenforcer.core.internal.json.JsonValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A Subject Search request: which subjects of this type may perform this action on this resource?
 *
 * <p>Serialized as the body of {@code POST /access/v1/search/subject}. The subject searched for is
 * given by its type only, since its identifier is what the search finds:
 * <pre>{@code
 * {
 *   "subject": {"type": "user"},
 *   "action": {"name": "can_read"},
 *   "resource": {"type": "account", "id": "123"}
 * }
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class SubjectSearchRequest {

    private final String subjectType;
    private final Map<String, Object> subjectProperties;
    private final Action action;
    private final Resource resource;
    private final Context context;
    private final PageRequest page;

    private SubjectSearchRequest(Builder builder) {
        this.subjectType = Checks.requireSet(builder.subjectType, "subjectType");
        this.subjectProperties = JsonValues.copyObject(builder.subjectProperties);
        this.action = Checks.requireSet(builder.action, "action");
        this.resource = Checks.requireSet(builder.resource, "resource");
        this.context = builder.context;
        this.page = builder.page;
    }

    /**
     * Creates a request without context or pagination.
     *
     * @param subjectType the type of the subjects searched for, such as {@code user}
     * @param action      the action
     * @param resource    the resource
     * @return the request
     * @throws IllegalStateException if an argument is {@code null}
     */
    public static SubjectSearchRequest of(String subjectType, Action action, Resource resource) {
        return builder().subjectType(subjectType).action(action).resource(resource).build();
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
     * Returns the type of the subjects searched for.
     *
     * @return the type
     */
    public String getSubjectType() {
        return subjectType;
    }

    /**
     * Returns the properties sent with the subject searched for.
     *
     * @return an unmodifiable map, empty if there are none
     */
    public Map<String, Object> getSubjectProperties() {
        return subjectProperties;
    }

    /**
     * Returns the action.
     *
     * @return the action
     */
    public Action getAction() {
        return action;
    }

    /**
     * Returns the resource.
     *
     * @return the resource
     */
    public Resource getResource() {
        return resource;
    }

    /**
     * Returns the environment context.
     *
     * @return the context, or empty if none was set
     */
    public Optional<Context> getContext() {
        return Optional.ofNullable(context);
    }

    /**
     * Returns the page asked for.
     *
     * @return the page, or empty to leave pagination to the PDP
     */
    public Optional<PageRequest> getPage() {
        return Optional.ofNullable(page);
    }

    /**
     * Returns a copy that asks for the page with the given token and keeps everything else, as
     * the specification requires for following pages.
     *
     * @param token the {@code next_token} of the previous response
     * @return the request for the next page
     */
    public SubjectSearchRequest withPageToken(String token) {
        PageRequest next = page != null ? page.withToken(token)
                : PageRequest.builder().token(Objects.requireNonNull(token, "token must not be null")).build();
        return builder().subjectType(subjectType).subjectProperties(subjectProperties).action(action)
                .resource(resource).context(context).page(next).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SubjectSearchRequest other)) {
            return false;
        }
        return subjectType.equals(other.subjectType) && subjectProperties.equals(other.subjectProperties)
                && action.equals(other.action) && resource.equals(other.resource)
                && Objects.equals(context, other.context) && Objects.equals(page, other.page);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subjectType, subjectProperties, action, resource, context, page);
    }

    /**
     * Returns a description for logging. Subject property values are masked, like those of a
     * {@link Subject}.
     */
    @Override
    public String toString() {
        return "SubjectSearchRequest{subjectType=" + subjectType + ", subjectProperties="
                + Redaction.maskValues(subjectProperties) + ", action=" + action + ", resource=" + resource
                + ", context=" + context + ", page=" + page + "}";
    }

    /**
     * Builder for {@link SubjectSearchRequest}. Not thread-safe.
     */
    public static final class Builder {

        private String subjectType;
        private final Map<String, Object> subjectProperties = new LinkedHashMap<>();
        private Action action;
        private Resource resource;
        private Context context;
        private PageRequest page;

        private Builder() {
        }

        /**
         * Sets the type of the subjects searched for. Required.
         *
         * @param subjectType the type, such as {@code user}
         * @return this builder
         */
        public Builder subjectType(String subjectType) {
            this.subjectType = subjectType;
            return this;
        }

        /**
         * Adds a property of the subject searched for, replacing any previous value with the same name.
         *
         * @param name  the property name
         * @param value a JSON-compatible value, may be {@code null}
         * @return this builder
         */
        public Builder subjectProperty(String name, Object value) {
            subjectProperties.put(Objects.requireNonNull(name, "name must not be null"), value);
            return this;
        }

        /**
         * Replaces all properties of the subject searched for.
         *
         * @param properties the properties, with JSON-compatible values
         * @return this builder
         */
        public Builder subjectProperties(Map<String, ?> properties) {
            Objects.requireNonNull(properties, "properties must not be null");
            this.subjectProperties.clear();
            this.subjectProperties.putAll(properties);
            return this;
        }

        /**
         * Sets the action. Required.
         *
         * @param action the action
         * @return this builder
         */
        public Builder action(Action action) {
            this.action = action;
            return this;
        }

        /**
         * Sets the resource. Required.
         *
         * @param resource the resource
         * @return this builder
         */
        public Builder resource(Resource resource) {
            this.resource = resource;
            return this;
        }

        /**
         * Sets the environment context. Optional.
         *
         * @param context the context, or {@code null} to send none
         * @return this builder
         */
        public Builder context(Context context) {
            this.context = context;
            return this;
        }

        /**
         * Sets the page asked for. Optional.
         *
         * @param page the page, or {@code null} to leave pagination to the PDP
         * @return this builder
         */
        public Builder page(PageRequest page) {
            this.page = page;
            return this;
        }

        /**
         * Builds the request. Subject properties are deep-copied.
         *
         * @return the request
         * @throws IllegalStateException    if the subject type, action or resource is not set
         * @throws IllegalArgumentException if a subject property value is not JSON-compatible
         */
        public SubjectSearchRequest build() {
            return new SubjectSearchRequest(this);
        }
    }
}
