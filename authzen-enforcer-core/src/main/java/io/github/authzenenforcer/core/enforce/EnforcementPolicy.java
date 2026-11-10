package io.github.authzenenforcer.core.enforce;

/**
 * How an {@link Enforcer} answers requests for which no decision could be obtained.
 *
 * <p>By default such requests are denied with {@value EnforcementResult#FORBIDDEN} (fail-closed).
 * A deny of the PDP, a request whose evaluation request could not be inferred and an anonymous
 * caller are always denied, whatever the policy says.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class EnforcementPolicy {

    private static final EnforcementPolicy DEFAULTS = builder().build();

    private final int errorStatus;
    private final boolean failOpen;

    private EnforcementPolicy(Builder builder) {
        this.errorStatus = builder.errorStatus;
        this.failOpen = builder.failOpen;
    }

    /**
     * Returns the default policy: fail-closed with {@value EnforcementResult#FORBIDDEN}.
     *
     * @return the policy
     */
    public static EnforcementPolicy defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a new builder with the default values.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the HTTP status of a request for which no decision could be obtained.
     *
     * @return {@value EnforcementResult#FORBIDDEN} or {@value EnforcementResult#SERVICE_UNAVAILABLE}
     */
    public int getErrorStatus() {
        return errorStatus;
    }

    /**
     * Returns whether requests are let through when the PDP gives no decision.
     *
     * @return {@code true} for fail-open
     */
    public boolean isFailOpen() {
        return failOpen;
    }

    @Override
    public String toString() {
        return "EnforcementPolicy{errorStatus=" + errorStatus + ", failOpen=" + failOpen + "}";
    }

    /**
     * Builder for {@link EnforcementPolicy}. Not thread-safe.
     */
    public static final class Builder {

        private int errorStatus = EnforcementResult.FORBIDDEN;
        private boolean failOpen;

        private Builder() {
        }

        /**
         * Sets the HTTP status of a request for which no decision could be obtained. Defaults to
         * {@value EnforcementResult#FORBIDDEN}, which tells the client nothing about the PDP;
         * {@value EnforcementResult#SERVICE_UNAVAILABLE} tells it to try again later.
         *
         * @param status {@value EnforcementResult#FORBIDDEN} or
         *               {@value EnforcementResult#SERVICE_UNAVAILABLE}
         * @return this builder
         * @throws IllegalArgumentException for any other status
         */
        public Builder errorStatus(int status) {
            if (status != EnforcementResult.FORBIDDEN && status != EnforcementResult.SERVICE_UNAVAILABLE) {
                throw new IllegalArgumentException("errorStatus must be " + EnforcementResult.FORBIDDEN + " or "
                        + EnforcementResult.SERVICE_UNAVAILABLE);
            }
            this.errorStatus = status;
            return this;
        }

        /**
         * Lets requests through when the PDP is unavailable: it cannot be reached, does not answer
         * in time, answers with a {@code 5xx} status or keeps answering {@code 429}, or its circuit
         * breaker is open, that is when the call fails with a
         * {@link io.github.authzenenforcer.core.PdpCommunicationException}. Off by default.
         *
         * <p>Fail-open applies to the whole application and only to an unavailable PDP. A deny, a
         * refused inference, an anonymous caller and a failure of an inference component still
         * deny, and so do failures that are not transient: a rejected PEP credential
         * ({@code 401}/{@code 403}), an invalid PDP response or metadata, a failing credential
         * provider, and a call cancelled by anyone but the caller. An enforcer with this policy logs a warning when it is built and whenever it
         * lets a request through without a decision, at most once a minute, and reports every such
         * request as {@link EnforcementResult.Outcome#FAILED_OPEN} to its listeners.
         *
         * @param failOpen whether to let requests through without a decision
         * @return this builder
         */
        public Builder failOpen(boolean failOpen) {
            this.failOpen = failOpen;
            return this;
        }

        /**
         * Builds the policy.
         *
         * @return the policy
         */
        public EnforcementPolicy build() {
            return new EnforcementPolicy(this);
        }
    }
}
