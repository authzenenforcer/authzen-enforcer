package io.github.authzenenforcer.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * One item of an Access Evaluations request.
 *
 * <p>Every part is optional: a part that is not set is taken from the defaults of the
 * {@link EvaluationsRequest} by the PDP. A part that is set replaces the default as a whole.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class EvaluationItem {

    private final Subject subject;
    private final Action action;
    private final Resource resource;
    private final Context context;

    private EvaluationItem(Builder builder) {
        this.subject = builder.subject;
        this.action = builder.action;
        this.resource = builder.resource;
        this.context = builder.context;
    }

    /**
     * Creates an item with all three required parts.
     *
     * @param subject  the subject
     * @param action   the action
     * @param resource the resource
     * @return the item
     * @throws NullPointerException if an argument is {@code null}
     */
    public static EvaluationItem of(Subject subject, Action action, Resource resource) {
        return builder()
                .subject(Objects.requireNonNull(subject, "subject must not be null"))
                .action(Objects.requireNonNull(action, "action must not be null"))
                .resource(Objects.requireNonNull(resource, "resource must not be null"))
                .build();
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
     * Returns the subject of this item.
     *
     * @return the subject, or empty if the default applies
     */
    public Optional<Subject> getSubject() {
        return Optional.ofNullable(subject);
    }

    /**
     * Returns the action of this item.
     *
     * @return the action, or empty if the default applies
     */
    public Optional<Action> getAction() {
        return Optional.ofNullable(action);
    }

    /**
     * Returns the resource of this item.
     *
     * @return the resource, or empty if the default applies
     */
    public Optional<Resource> getResource() {
        return Optional.ofNullable(resource);
    }

    /**
     * Returns the context of this item.
     *
     * @return the context, or empty if the default applies
     */
    public Optional<Context> getContext() {
        return Optional.ofNullable(context);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EvaluationItem other)) {
            return false;
        }
        return Objects.equals(subject, other.subject) && Objects.equals(action, other.action)
                && Objects.equals(resource, other.resource) && Objects.equals(context, other.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, action, resource, context);
    }

    @Override
    public String toString() {
        return "EvaluationItem{subject=" + subject + ", action=" + action + ", resource=" + resource
                + ", context=" + context + "}";
    }

    /**
     * Builder for {@link EvaluationItem}. Not thread-safe.
     */
    public static final class Builder {

        private Subject subject;
        private Action action;
        private Resource resource;
        private Context context;

        private Builder() {
        }

        /**
         * Sets the subject, replacing the default.
         *
         * @param subject the subject, or {@code null} for the default
         * @return this builder
         */
        public Builder subject(Subject subject) {
            this.subject = subject;
            return this;
        }

        /**
         * Sets the action, replacing the default.
         *
         * @param action the action, or {@code null} for the default
         * @return this builder
         */
        public Builder action(Action action) {
            this.action = action;
            return this;
        }

        /**
         * Sets the resource, replacing the default.
         *
         * @param resource the resource, or {@code null} for the default
         * @return this builder
         */
        public Builder resource(Resource resource) {
            this.resource = resource;
            return this;
        }

        /**
         * Sets the context, replacing the default as a whole. An empty context is not the same as
         * none: it is sent as {@code {}} and leaves this item without the default context.
         *
         * @param context the context, or {@code null} for the default
         * @return this builder
         */
        public Builder context(Context context) {
            this.context = context;
            return this;
        }

        /**
         * Builds the item.
         *
         * @return the item
         */
        public EvaluationItem build() {
            return new EvaluationItem(this);
        }
    }
}
