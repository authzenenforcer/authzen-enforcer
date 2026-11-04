package io.github.authzenenforcer.core.model;

import io.github.authzenenforcer.core.internal.json.JsonValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A Resource Search request: on which resources of this type may this subject perform this action?
 *
 * <p>Serialized as the body of {@code POST /access/v1/search/resource}. The resource searched for
 * is given by its type only, since its identifier is what the search finds:
 * <pre>{@code
 * {
 *   "subject": {"type": "user", "id": "alice@example.com"},
 *   "action": {"name": "can_read"},
 *   "resource": {"type": "account"}
 * }
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class ResourceSearchRequest {

    private final Subject subject;
    private final Action action;
    private final String resourceType;
    private final Map<String, Object> resourceProperties;
    private final Context context;
    private final PageRequest page;

    private ResourceSearchRequest(Builder builder) {
        this.subject = Checks.requireSet(builder.subject, "subject");
        this.action = Checks.requireSet(builder.action, "action");
        this.resourceType = Checks.requireSet(builder.resourceType, "resourceType");
        this.resourceProperties = JsonValues.copyObject(builder.resourceProperties);
        this.context = builder.context;
        this.page = builder.page;
    }

    /**
     * Creates a request without context or pagination.
     *
     * @param subject      the subject
     * @param action       the action
     * @param resourceType the type of the resources searched for, such as {@code document}
     * @return the request
     * @throws IllegalStateException if an argument is {@code null}
     */
    public static ResourceSearchRequest of(Subject subject, Action action, String resourceType) {
        return builder().subject(subject).action(action).resourceType(resourceType).build();
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
     * Returns the subject.
     *
     * @return the subject
     */
    public Subject getSubject() {
        return subject;
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
     * Returns the type of the resources searched for.
     *
     * @return the type
     */
    public String getResourceType() {
        return resourceType;
    }

    /**
     * Returns the properties sent with the resource searched for.
     *
     * @return an unmodifiable map, empty if there are none
     */
    public Map<String, Object> getResourceProperties() {
        return resourceProperties;
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
    public ResourceSearchRequest withPageToken(String token) {
        PageRequest next = page != null ? page.withToken(token)
                : PageRequest.builder().token(Objects.requireNonNull(token, "token must not be null")).build();
        return builder().subject(subject).action(action).resourceType(resourceType)
                .resourceProperties(resourceProperties).context(context).page(next).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResourceSearchRequest other)) {
            return false;
        }
        return subject.equals(other.subject) && action.equals(other.action)
                && resourceType.equals(other.resourceType) && resourceProperties.equals(other.resourceProperties)
                && Objects.equals(context, other.context) && Objects.equals(page, other.page);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, action, resourceType, resourceProperties, context, page);
    }

    /**
     * Returns a description for logging. Resource property values are masked, like those of a
     * {@link Resource}.
     */
    @Override
    public String toString() {
        return "ResourceSearchRequest{subject=" + subject + ", action=" + action + ", resourceType="
                + resourceType + ", resourceProperties=" + Redaction.maskValues(resourceProperties)
                + ", context=" + context + ", page=" + page + "}";
    }

    /**
     * Builder for {@link ResourceSearchRequest}. Not thread-safe.
     */
    public static final class Builder {

        private Subject subject;
        private Action action;
        private String resourceType;
        private final Map<String, Object> resourceProperties = new LinkedHashMap<>();
        private Context context;
        private PageRequest page;

        private Builder() {
        }

        /**
         * Sets the subject. Required.
         *
         * @param subject the subject
         * @return this builder
         */
        public Builder subject(Subject subject) {
            this.subject = subject;
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
         * Sets the type of the resources searched for. Required.
         *
         * @param resourceType the type, such as {@code document}
         * @return this builder
         */
        public Builder resourceType(String resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        /**
         * Adds a property of the resource searched for, replacing any previous value with the same name.
         *
         * @param name  the property name
         * @param value a JSON-compatible value, may be {@code null}
         * @return this builder
         */
        public Builder resourceProperty(String name, Object value) {
            resourceProperties.put(Objects.requireNonNull(name, "name must not be null"), value);
            return this;
        }

        /**
         * Replaces all properties of the resource searched for.
         *
         * @param properties the properties, with JSON-compatible values
         * @return this builder
         */
        public Builder resourceProperties(Map<String, ?> properties) {
            Objects.requireNonNull(properties, "properties must not be null");
            this.resourceProperties.clear();
            this.resourceProperties.putAll(properties);
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
         * Builds the request. Resource properties are deep-copied.
         *
         * @return the request
         * @throws IllegalStateException    if the subject, action or resource type is not set
         * @throws IllegalArgumentException if a resource property value is not JSON-compatible
         */
        public ResourceSearchRequest build() {
            return new ResourceSearchRequest(this);
        }
    }
}
