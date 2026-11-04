package io.github.authzenenforcer.core.model;

import io.github.authzenenforcer.core.internal.json.JsonValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An Access Evaluations request: several decisions asked for in one call.
 *
 * <p>Serialized as the AuthZEN batch request. The top-level parts are defaults for every item; an
 * item part replaces the default part as a whole:
 * <pre>{@code
 * {"subject": {"type": "user", "id": "alice@example.com"},
 *  "action": {"name": "can_read"},
 *  "evaluations": [
 *    {"resource": {"type": "document", "id": "boxcarring.md"}},
 *    {"resource": {"type": "document", "id": "subject-search.md"}},
 *    {"action": {"name": "can_edit"}, "resource": {"type": "document", "id": "resource-search.md"}}],
 *  "options": {"evaluations_semantic": "deny_on_first_deny"}}
 * }</pre>
 *
 * <p>The request is sent as built; the PDP applies the defaults. With no items it is a single
 * evaluation of the defaults.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class EvaluationsRequest {

    private final Subject subject;
    private final Action action;
    private final Resource resource;
    private final Context context;
    private final List<EvaluationItem> evaluations;
    private final EvaluationsSemantic semantic;
    private final Map<String, Object> options;

    private EvaluationsRequest(Builder builder) {
        this.subject = builder.subject;
        this.action = builder.action;
        this.resource = builder.resource;
        this.context = builder.context;
        this.evaluations = List.copyOf(builder.evaluations);
        this.semantic = builder.semantic;
        this.options = JsonValues.copyObject(builder.options);
        // The specification requires every evaluation to end up with these three parts
        if (evaluations.isEmpty()) {
            requireDefault(subject, "subject", -1);
            requireDefault(action, "action", -1);
            requireDefault(resource, "resource", -1);
        }
        for (int i = 0; i < evaluations.size(); i++) {
            EvaluationItem item = evaluations.get(i);
            requireDefault(item.getSubject().isPresent() ? item : subject, "subject", i);
            requireDefault(item.getAction().isPresent() ? item : action, "action", i);
            requireDefault(item.getResource().isPresent() ? item : resource, "resource", i);
        }
        // A lenient PDP might read a variant of the name, and then disagree with the semantic checked here
        for (String name : options.keySet()) {
            if (name.strip().equalsIgnoreCase("evaluations_semantic")) {
                throw new IllegalStateException("set evaluations_semantic with semantic(...), not as an option");
            }
        }
    }

    private static void requireDefault(Object value, String part, int item) {
        if (value == null) {
            throw new IllegalStateException(item < 0 ? part + " is required when there are no evaluations"
                    : "evaluation " + item + " has no " + part + " and no default " + part + " is set");
        }
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
     * Returns the default subject.
     *
     * @return the subject, or empty if every item sets its own
     */
    public Optional<Subject> getSubject() {
        return Optional.ofNullable(subject);
    }

    /**
     * Returns the default action.
     *
     * @return the action, or empty if every item sets its own
     */
    public Optional<Action> getAction() {
        return Optional.ofNullable(action);
    }

    /**
     * Returns the default resource.
     *
     * @return the resource, or empty if every item sets its own
     */
    public Optional<Resource> getResource() {
        return Optional.ofNullable(resource);
    }

    /**
     * Returns the default context.
     *
     * @return the context, or empty if none was set
     */
    public Optional<Context> getContext() {
        return Optional.ofNullable(context);
    }

    /**
     * Returns the items.
     *
     * @return an unmodifiable list, in the order the decisions are returned; empty for a single
     *         evaluation of the defaults
     */
    public List<EvaluationItem> getEvaluations() {
        return evaluations;
    }

    /**
     * Returns how the PDP executes the items.
     *
     * @return the semantic set, or empty for the PDP default ({@link EvaluationsSemantic#EXECUTE_ALL})
     */
    public Optional<EvaluationsSemantic> getSemantic() {
        return Optional.ofNullable(semantic);
    }

    /**
     * Returns options other than {@code evaluations_semantic}.
     *
     * @return an unmodifiable map, empty if there are none
     */
    public Map<String, Object> getOptions() {
        return options;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EvaluationsRequest other)) {
            return false;
        }
        return Objects.equals(subject, other.subject) && Objects.equals(action, other.action)
                && Objects.equals(resource, other.resource) && Objects.equals(context, other.context)
                && evaluations.equals(other.evaluations) && semantic == other.semantic
                && options.equals(other.options);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, action, resource, context, evaluations, semantic, options);
    }

    /**
     * Returns a description for logging. Option values are masked, like property values.
     */
    @Override
    public String toString() {
        return "EvaluationsRequest{subject=" + subject + ", action=" + action + ", resource=" + resource
                + ", context=" + context + ", evaluations=" + evaluations + ", semantic=" + semantic
                + ", options=" + Redaction.maskValues(options) + "}";
    }

    /**
     * Builder for {@link EvaluationsRequest}. Not thread-safe.
     */
    public static final class Builder {

        private Subject subject;
        private Action action;
        private Resource resource;
        private Context context;
        private final List<EvaluationItem> evaluations = new ArrayList<>();
        private EvaluationsSemantic semantic;
        private final Map<String, Object> options = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the default subject.
         *
         * @param subject the subject, or {@code null} if every item sets its own
         * @return this builder
         */
        public Builder subject(Subject subject) {
            this.subject = subject;
            return this;
        }

        /**
         * Sets the default action.
         *
         * @param action the action, or {@code null} if every item sets its own
         * @return this builder
         */
        public Builder action(Action action) {
            this.action = action;
            return this;
        }

        /**
         * Sets the default resource.
         *
         * @param resource the resource, or {@code null} if every item sets its own
         * @return this builder
         */
        public Builder resource(Resource resource) {
            this.resource = resource;
            return this;
        }

        /**
         * Sets the default context.
         *
         * @param context the context, or {@code null} for none
         * @return this builder
         */
        public Builder context(Context context) {
            this.context = context;
            return this;
        }

        /**
         * Adds an item.
         *
         * @param evaluation the item
         * @return this builder
         */
        public Builder evaluation(EvaluationItem evaluation) {
            evaluations.add(Objects.requireNonNull(evaluation, "evaluation must not be null"));
            return this;
        }

        /**
         * Replaces all items.
         *
         * @param evaluations the items, in order
         * @return this builder
         */
        public Builder evaluations(List<EvaluationItem> evaluations) {
            Objects.requireNonNull(evaluations, "evaluations must not be null");
            this.evaluations.clear();
            evaluations.forEach(this::evaluation);
            return this;
        }

        /**
         * Sets how the PDP executes the items. Without it, the PDP uses
         * {@link EvaluationsSemantic#EXECUTE_ALL}.
         *
         * @param semantic the semantic
         * @return this builder
         */
        public Builder semantic(EvaluationsSemantic semantic) {
            this.semantic = Objects.requireNonNull(semantic, "semantic must not be null");
            return this;
        }

        /**
         * Adds an option other than {@code evaluations_semantic}, which {@link #semantic} sets. A name
         * that differs from {@code evaluations_semantic} only in case or surrounding whitespace is
         * rejected as well. Some PDPs, such as Keycloak 26.8, reject any option they do not know.
         *
         * @param name  the option name
         * @param value a JSON-compatible value
         * @return this builder
         */
        public Builder option(String name, Object value) {
            options.put(Objects.requireNonNull(name, "name must not be null"), value);
            return this;
        }

        /**
         * Builds the request.
         *
         * @return the request
         * @throws IllegalStateException    if an item would have no subject, action or resource, or
         *                                  {@code evaluations_semantic} was given as an option
         * @throws IllegalArgumentException if an option value is not JSON-compatible
         */
        public EvaluationsRequest build() {
            return new EvaluationsRequest(this);
        }
    }
}
