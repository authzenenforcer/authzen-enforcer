package io.github.authzenenforcer.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * An Action Search request: which actions may this subject perform on this resource?
 *
 * <p>Serialized as the body of {@code POST /access/v1/search/action}, which has no {@code action}:
 * <pre>{@code
 * {
 *   "subject": {"type": "user", "id": "alice@example.com"},
 *   "resource": {"type": "account", "id": "123"}
 * }
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class ActionSearchRequest {

    private final Subject subject;
    private final Resource resource;
    private final Context context;
    private final PageRequest page;

    private ActionSearchRequest(Builder builder) {
        this.subject = Checks.requireSet(builder.subject, "subject");
        this.resource = Checks.requireSet(builder.resource, "resource");
        this.context = builder.context;
        this.page = builder.page;
    }

    /**
     * Creates a request without context or pagination.
     *
     * @param subject  the subject
     * @param resource the resource
     * @return the request
     * @throws IllegalStateException if an argument is {@code null}
     */
    public static ActionSearchRequest of(Subject subject, Resource resource) {
        return builder().subject(subject).resource(resource).build();
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
    public ActionSearchRequest withPageToken(String token) {
        PageRequest next = page != null ? page.withToken(token)
                : PageRequest.builder().token(Objects.requireNonNull(token, "token")).build();
        return builder().subject(subject).resource(resource).context(context).page(next).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ActionSearchRequest other)) {
            return false;
        }
        return subject.equals(other.subject) && resource.equals(other.resource)
                && Objects.equals(context, other.context) && Objects.equals(page, other.page);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, resource, context, page);
    }

    @Override
    public String toString() {
        return "ActionSearchRequest{subject=" + subject + ", resource=" + resource + ", context=" + context
                + ", page=" + page + "}";
    }

    /**
     * Builder for {@link ActionSearchRequest}. Not thread-safe.
     */
    public static final class Builder {

        private Subject subject;
        private Resource resource;
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
         * Builds the request.
         *
         * @return the request
         * @throws IllegalStateException if the subject or resource is not set
         */
        public ActionSearchRequest build() {
            return new ActionSearchRequest(this);
        }
    }
}
