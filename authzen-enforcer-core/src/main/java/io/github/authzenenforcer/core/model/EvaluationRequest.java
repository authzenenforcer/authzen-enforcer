package io.github.authzenenforcer.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * An Access Evaluation request: may this subject perform this action on this resource?
 *
 * <p>Serialized as the body of {@code POST /access/v1/evaluation}:
 * <pre>{@code
 * {
 *   "subject": {"type": "user", "id": "alice@example.com"},
 *   "action": {"name": "can_read"},
 *   "resource": {"type": "account", "id": "123"},
 *   "context": {"time": "1985-10-26T01:22-07:00"}
 * }
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class EvaluationRequest {

    private final Subject subject;
    private final Action action;
    private final Resource resource;
    private final Context context;

    private EvaluationRequest(Builder builder) {
        this.subject = Checks.requireSet(builder.subject, "subject");
        this.action = Checks.requireSet(builder.action, "action");
        this.resource = Checks.requireSet(builder.resource, "resource");
        this.context = builder.context;
    }

    /**
     * Creates a request without context.
     *
     * @param subject  the subject
     * @param action   the action
     * @param resource the resource
     * @return the request
     * @throws IllegalStateException if an argument is {@code null}
     */
    public static EvaluationRequest of(Subject subject, Action action, Resource resource) {
        return builder().subject(subject).action(action).resource(resource).build();
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EvaluationRequest other)) {
            return false;
        }
        return subject.equals(other.subject) && action.equals(other.action)
                && resource.equals(other.resource) && Objects.equals(context, other.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, action, resource, context);
    }

    @Override
    public String toString() {
        return "EvaluationRequest{subject=" + subject + ", action=" + action + ", resource=" + resource
                + ", context=" + context + "}";
    }

    /**
     * Builder for {@link EvaluationRequest}. Not thread-safe.
     */
    public static final class Builder {

        private Subject subject;
        private Action action;
        private Resource resource;
        private Context context;

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
         * Builds the request.
         *
         * @return the request
         * @throws IllegalStateException if the subject, action or resource is not set
         */
        public EvaluationRequest build() {
            return new EvaluationRequest(this);
        }
    }
}
