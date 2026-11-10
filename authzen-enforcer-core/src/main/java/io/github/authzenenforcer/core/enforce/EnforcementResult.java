package io.github.authzenenforcer.core.enforce;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What an {@link Enforcer} decided about a request.
 *
 * <p>Framework integrations let the request through if {@link #isAllowed()}, and otherwise answer
 * with {@link #getStatus()}. The PDP's response, with any reason in its context, is available to
 * a hook of the integration but is not meant to reach the client as it is.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class EnforcementResult {

    /** HTTP status for a caller without an identity. */
    public static final int UNAUTHORIZED = 401;

    /** HTTP status for a denied request. */
    public static final int FORBIDDEN = 403;

    /** HTTP status that a policy may choose for a request without a decision. */
    public static final int SERVICE_UNAVAILABLE = 503;

    /** How a request was handled. */
    public enum Outcome {

        /** The PDP permitted the request. */
        PERMITTED(true),

        /** The PDP denied the request. */
        DENIED(false),

        /** The request is not authorized at all, such as a CORS preflight ({@code OPTIONS}). */
        NOT_ENFORCED(true),

        /** The caller is anonymous and no anonymous subject is configured; the PDP was not asked. */
        UNAUTHENTICATED(false),

        /** Nothing could be inferred safely, so the PDP was not asked; see {@link #getReason()}. */
        REFUSED(false),

        /** No decision could be obtained; see {@link #getFailure()}. */
        FAILED(false),

        /**
         * The PDP is unavailable, and the policy lets such requests through; see
         * {@link EnforcementPolicy.Builder#failOpen(boolean)} and {@link #getFailure()}.
         */
        FAILED_OPEN(true);

        private final boolean allowed;

        Outcome(boolean allowed) {
            this.allowed = allowed;
        }

        /**
         * Returns whether a request with this outcome is let through.
         *
         * @return {@code true} for {@link #PERMITTED}, {@link #NOT_ENFORCED} and {@link #FAILED_OPEN}
         */
        public boolean isAllowed() {
            return allowed;
        }
    }

    private static final EnforcementResult NOT_ENFORCED = new EnforcementResult(Outcome.NOT_ENFORCED, 0, null, null,
            null, null);
    private static final EnforcementResult UNAUTHENTICATED = new EnforcementResult(Outcome.UNAUTHENTICATED,
            UNAUTHORIZED, null, null, null, null);

    private final Outcome outcome;
    private final int status;
    private final EvaluationRequest request;
    private final EvaluationResponse response;
    private final String reason;
    private final AuthZenException failure;

    private EnforcementResult(Outcome outcome, int status, EvaluationRequest request, EvaluationResponse response,
            String reason, AuthZenException failure) {
        this.outcome = outcome;
        this.status = status;
        this.request = request;
        this.response = response;
        this.reason = reason;
        this.failure = failure;
    }

    static EnforcementResult decided(EvaluationRequest request, EvaluationResponse response) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(response, "response must not be null");
        boolean permitted = response.getDecision();
        return new EnforcementResult(permitted ? Outcome.PERMITTED : Outcome.DENIED, permitted ? 0 : FORBIDDEN,
                request, response, null, null);
    }

    static EnforcementResult notEnforced() {
        return NOT_ENFORCED;
    }

    static EnforcementResult unauthenticated() {
        return UNAUTHENTICATED;
    }

    static EnforcementResult refused(String reason) {
        return new EnforcementResult(Outcome.REFUSED, FORBIDDEN, null, null,
                Objects.requireNonNull(reason, "reason must not be null"), null);
    }

    static EnforcementResult failed(EvaluationRequest request, AuthZenException failure, int status) {
        return new EnforcementResult(Outcome.FAILED, status, request, null, null,
                Objects.requireNonNull(failure, "failure must not be null"));
    }

    static EnforcementResult failedOpen(EvaluationRequest request, AuthZenException failure) {
        return new EnforcementResult(Outcome.FAILED_OPEN, 0, request, null, null,
                Objects.requireNonNull(failure, "failure must not be null"));
    }

    /**
     * Returns how the request was handled.
     *
     * @return the outcome
     */
    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * Returns whether the request is let through.
     *
     * @return {@code true} for {@link Outcome#PERMITTED}, {@link Outcome#NOT_ENFORCED} and
     *         {@link Outcome#FAILED_OPEN}
     */
    public boolean isAllowed() {
        return outcome.isAllowed();
    }

    /**
     * Returns the HTTP status to answer a request that is not let through with:
     * {@value #UNAUTHORIZED} for {@link Outcome#UNAUTHENTICATED}, {@value #FORBIDDEN} for
     * {@link Outcome#DENIED} and {@link Outcome#REFUSED}, and the
     * {@linkplain EnforcementPolicy#getErrorStatus() policy's error status} for
     * {@link Outcome#FAILED}.
     *
     * <p>A {@value #UNAUTHORIZED} response must carry a {@code WWW-Authenticate} challenge (RFC 9110,
     * section 15.5.2), whose scheme only the framework's authentication knows; integrations leave the
     * response to it, for example to Spring Security's authentication entry point.
     *
     * @return the status, or empty if the request is let through
     */
    public OptionalInt getStatus() {
        return status == 0 ? OptionalInt.empty() : OptionalInt.of(status);
    }

    /**
     * Returns the evaluation request that was sent to the PDP, or was to be sent.
     *
     * @return the request, empty if the PDP was not asked because nothing was to be evaluated
     */
    public Optional<EvaluationRequest> getRequest() {
        return Optional.ofNullable(request);
    }

    /**
     * Returns the PDP's response. Its context may explain a denial; it is meant for logs and hooks,
     * not for the client, unless the application decides so.
     *
     * @return the response for {@link Outcome#PERMITTED} and {@link Outcome#DENIED}, otherwise empty
     */
    public Optional<EvaluationResponse> getResponse() {
        return Optional.ofNullable(response);
    }

    /**
     * Returns why nothing could be inferred. The reason names the route and the variable or claim,
     * never a value taken from the request.
     *
     * @return the reason for {@link Outcome#REFUSED}, otherwise empty
     */
    public Optional<String> getReason() {
        return Optional.ofNullable(reason);
    }

    /**
     * Returns why no decision could be obtained.
     *
     * @return the failure for {@link Outcome#FAILED} and {@link Outcome#FAILED_OPEN}, otherwise empty
     */
    public Optional<AuthZenException> getFailure() {
        return Optional.ofNullable(failure);
    }

    // The request is left out: its subject and properties are personal data, and the
    // CallCompleted event of the client carries it for those who need it
    @Override
    public String toString() {
        return "EnforcementResult{" + outcome + (status != 0 ? ", status=" + status : "")
                + (reason != null ? ", " + reason : "")
                + (failure != null ? ", failure=" + failure.getClass().getName() : "") + "}";
    }
}
