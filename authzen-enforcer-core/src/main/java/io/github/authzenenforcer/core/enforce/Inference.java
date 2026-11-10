package io.github.authzenenforcer.core.enforce;

import io.github.authzenenforcer.core.model.EvaluationRequest;

import java.util.Objects;
import java.util.Optional;

/**
 * What a {@link RequestInferrer} made of a request.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class Inference {

    /** How a request is to be handled. */
    public enum Kind {

        /** The PDP is to evaluate the request in {@link #getRequest()}. */
        EVALUATE,

        /** The request is not authorized at all, such as a CORS preflight ({@code OPTIONS}). */
        NOT_ENFORCED,

        /** The caller is anonymous and no anonymous subject is configured. The request is denied. */
        UNAUTHENTICATED,

        /**
         * Nothing could be inferred safely; {@link #getReason()} says why. The request is denied
         * without asking the PDP.
         */
        REFUSED
    }

    private static final Inference NOT_ENFORCED = new Inference(Kind.NOT_ENFORCED, null, null, false);
    private static final Inference UNAUTHENTICATED = new Inference(Kind.UNAUTHENTICATED, null, null, false);

    private final Kind kind;
    private final EvaluationRequest request;
    private final String reason;
    private final boolean misconfiguration;

    private Inference(Kind kind, EvaluationRequest request, String reason, boolean misconfiguration) {
        this.kind = kind;
        this.request = request;
        this.reason = reason;
        this.misconfiguration = misconfiguration;
    }

    static Inference evaluate(EvaluationRequest request) {
        return new Inference(Kind.EVALUATE, Objects.requireNonNull(request, "request must not be null"), null, false);
    }

    static Inference notEnforced() {
        return NOT_ENFORCED;
    }

    static Inference unauthenticated() {
        return UNAUTHENTICATED;
    }

    static Inference refused(InferenceException reason) {
        return new Inference(Kind.REFUSED, null, reason.getMessage(), reason.isMisconfiguration());
    }

    /**
     * Returns how the request is to be handled.
     *
     * @return the kind
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Returns the evaluation request for the PDP.
     *
     * @return the request for {@link Kind#EVALUATE}, otherwise empty
     */
    public Optional<EvaluationRequest> getRequest() {
        return Optional.ofNullable(request);
    }

    /**
     * Returns why nothing could be inferred. The reason names the route and the variable or claim,
     * never a value taken from the request.
     *
     * @return the reason for {@link Kind#REFUSED}, otherwise empty
     */
    public Optional<String> getReason() {
        return Optional.ofNullable(reason);
    }

    /**
     * Returns whether a refusal comes from the endpoint's configuration rather than from the input
     * of this request; see {@link InferenceException#isMisconfiguration()}.
     *
     * @return {@code true} for a refusal caused by a misconfiguration
     */
    public boolean isMisconfiguration() {
        return misconfiguration;
    }

    @Override
    public String toString() {
        return "Inference{" + kind + (request != null ? ", " + request : "") + (reason != null ? ", " + reason : "") + "}";
    }
}
