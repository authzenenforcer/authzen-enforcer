package io.github.authzenenforcer.core.event;

import io.github.authzenenforcer.core.enforce.EnforcementResult;
import io.github.authzenenforcer.core.internal.text.LogText;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A request that an {@link io.github.authzenenforcer.core.enforce.Enforcer} has handled, or whose
 * caller cancelled it.
 *
 * <p>The event carries no subject, resource or PDP response: the
 * {@link CallCompleted} event of the client reports the evaluation, masked as its
 * {@link EventContent} says. The route pattern is the one the framework matched, so the number of
 * routes is bounded; the method is the caller's and may be any token.
 *
 * <p>Instances are immutable and thread-safe. The SDK creates them; the builder lets tests of a
 * listener create their own.
 */
public final class EnforcementCompleted {

    private final String method;
    private final String routePattern;
    private final EnforcementResult.Outcome outcome;
    private final String reason;
    private final Throwable failure;
    private final Duration duration;
    private final Instant time;

    private EnforcementCompleted(Builder builder) {
        this.method = Objects.requireNonNull(builder.method, "method is required");
        this.routePattern = Objects.requireNonNull(builder.routePattern, "routePattern is required");
        this.outcome = builder.outcome;
        this.reason = builder.reason;
        this.failure = builder.failure;
        this.duration = Objects.requireNonNull(builder.duration, "duration is required");
        this.time = Objects.requireNonNull(builder.time, "time is required");
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
     * Returns the HTTP method of the request.
     *
     * @return the method as the caller sent it
     */
    public String getMethod() {
        return method;
    }

    /**
     * Returns the route pattern the framework matched.
     *
     * @return the route pattern
     */
    public String getRoutePattern() {
        return routePattern;
    }

    /**
     * Returns how the request was handled.
     *
     * @return the outcome, or empty if the caller cancelled the request first
     */
    public Optional<EnforcementResult.Outcome> getOutcome() {
        return Optional.ofNullable(outcome);
    }

    /**
     * Returns whether the caller cancelled the request, or was interrupted, before it was decided.
     * Such a request got no result; the PDP may or may not have seen it.
     *
     * @return {@code true} if the request was cancelled
     */
    public boolean isCancelled() {
        return outcome == null;
    }

    /**
     * Returns why nothing could be inferred; see {@link EnforcementResult#getReason()}.
     *
     * @return the reason for {@link EnforcementResult.Outcome#REFUSED}, otherwise empty
     */
    public Optional<String> getReason() {
        return Optional.ofNullable(reason);
    }

    /**
     * Returns why no decision could be obtained. Its cause chain may hold messages of integrator
     * code; see {@link io.github.authzenenforcer.core.spi.PepListener}.
     *
     * @return the failure for {@link EnforcementResult.Outcome#FAILED} and
     *         {@link EnforcementResult.Outcome#FAILED_OPEN}, otherwise empty
     */
    public Optional<Throwable> getFailure() {
        return Optional.ofNullable(failure);
    }

    /**
     * Returns how long the enforcement took, from inference to the result.
     *
     * @return the duration
     */
    public Duration getDuration() {
        return duration;
    }

    /**
     * Returns when the enforcement completed, on the wall clock.
     *
     * @return the time
     */
    public Instant getTime() {
        return time;
    }

    @Override
    public String toString() {
        return "EnforcementCompleted{" + LogText.of(method) + " " + LogText.of(routePattern) + ", "
                + (outcome != null ? outcome : "CANCELLED")
                + (reason != null ? ", " + reason : "")
                + (failure != null ? ", failure=" + failure.getClass().getName() : "")
                + ", duration=" + duration.toMillis() + "ms}";
    }

    /**
     * Builder for {@link EnforcementCompleted}. Not thread-safe.
     */
    public static final class Builder {

        private String method;
        private String routePattern;
        private EnforcementResult.Outcome outcome;
        private String reason;
        private Throwable failure;
        private Duration duration;
        private Instant time;

        private Builder() {
        }

        /**
         * Sets the HTTP method of the request.
         *
         * @param method the method
         * @return this builder
         */
        public Builder method(String method) {
            this.method = Objects.requireNonNull(method, "method must not be null");
            return this;
        }

        /**
         * Sets the route pattern the framework matched.
         *
         * @param routePattern the route pattern
         * @return this builder
         */
        public Builder routePattern(String routePattern) {
            this.routePattern = Objects.requireNonNull(routePattern, "routePattern must not be null");
            return this;
        }

        /**
         * Sets how the request was handled. Not set for a cancelled request.
         *
         * @param outcome the outcome
         * @return this builder
         */
        public Builder outcome(EnforcementResult.Outcome outcome) {
            this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
            return this;
        }

        /**
         * Sets why nothing could be inferred.
         *
         * @param reason the reason, or {@code null} for none
         * @return this builder
         */
        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        /**
         * Sets why no decision could be obtained.
         *
         * @param failure the failure, or {@code null} for none
         * @return this builder
         */
        public Builder failure(Throwable failure) {
            this.failure = failure;
            return this;
        }

        /**
         * Sets how long the enforcement took.
         *
         * @param duration the duration
         * @return this builder
         */
        public Builder duration(Duration duration) {
            this.duration = Objects.requireNonNull(duration, "duration must not be null");
            return this;
        }

        /**
         * Sets when the enforcement completed.
         *
         * @param time the time
         * @return this builder
         */
        public Builder time(Instant time) {
            this.time = Objects.requireNonNull(time, "time must not be null");
            return this;
        }

        /**
         * Builds the event.
         *
         * @return the event
         * @throws NullPointerException if a required value is missing
         */
        public EnforcementCompleted build() {
            return new EnforcementCompleted(this);
        }
    }
}
