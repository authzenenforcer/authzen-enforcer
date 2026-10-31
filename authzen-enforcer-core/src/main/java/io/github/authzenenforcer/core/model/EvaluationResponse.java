package io.github.authzenenforcer.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * The decision of the PDP for an Access Evaluation request.
 *
 * <p>Deserialized from the response body:
 * <pre>{@code
 * {
 *   "decision": false,
 *   "context": {"reason_user": {"403": "Insufficient privileges. Contact your administrator"}}
 * }
 * }</pre>
 *
 * <p>A deny is a regular decision, not an error. Instances are immutable and thread-safe.
 */
public final class EvaluationResponse {

    private static final EvaluationResponse PERMIT = new EvaluationResponse(true, null);
    private static final EvaluationResponse DENY = new EvaluationResponse(false, null);

    private final boolean decision;
    private final Context context;

    private EvaluationResponse(boolean decision, Context context) {
        this.decision = decision;
        this.context = context;
    }

    /**
     * Returns a response without context.
     *
     * @param decision {@code true} to permit, {@code false} to deny
     * @return the response
     */
    public static EvaluationResponse of(boolean decision) {
        return decision ? PERMIT : DENY;
    }

    /**
     * Creates a response with context.
     *
     * @param decision {@code true} to permit, {@code false} to deny
     * @param context  the context returned by the PDP
     * @return the response
     */
    public static EvaluationResponse of(boolean decision, Context context) {
        return new EvaluationResponse(decision, Objects.requireNonNull(context, "context"));
    }

    /**
     * Returns the decision.
     *
     * @return {@code true} if the request is permitted, {@code false} if it is denied
     */
    public boolean getDecision() {
        return decision;
    }

    /**
     * Returns the context the PDP returned with the decision, for example a reason or advice.
     *
     * @return the context, or empty if the PDP returned none
     */
    public Optional<Context> getContext() {
        return Optional.ofNullable(context);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EvaluationResponse other)) {
            return false;
        }
        return decision == other.decision && Objects.equals(context, other.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(decision, context);
    }

    @Override
    public String toString() {
        return "EvaluationResponse{decision=" + decision + ", context=" + context + "}";
    }
}
