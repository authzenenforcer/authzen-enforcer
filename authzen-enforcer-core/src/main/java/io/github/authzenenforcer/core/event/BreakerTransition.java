package io.github.authzenenforcer.core.event;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A circuit breaker of the client that opened or closed.
 *
 * <p>Events report a breaker that opens after repeated failures, opens again after a failed
 * probe, or closes after a successful one. The short half-open state in which the probe runs is
 * visible as the {@link #getFrom() from} state of the next event.
 *
 * <p>Instances are immutable and thread-safe. The SDK creates them; the builder lets tests of a
 * listener create their own.
 *
 * @see io.github.authzenenforcer.core.client.CircuitBreakerOptions
 */
public final class BreakerTransition {

    /** Which breaker changed. */
    public enum Breaker {

        /** The breaker of the metadata request. */
        METADATA_DISCOVERY,

        /** The breaker shared by evaluations, batch evaluations and searches. */
        AUTHORIZATION_API
    }

    /** The state of a breaker. */
    public enum State {

        /** Calls pass. */
        CLOSED,

        /** Calls fail at once without contacting the PDP. */
        OPEN,

        /** A single probe call passes; the others fail at once. */
        HALF_OPEN
    }

    private final Breaker breaker;
    private final State from;
    private final State to;
    private final Duration openDuration;
    private final Throwable failure;
    private final Instant time;

    private BreakerTransition(Builder builder) {
        this.breaker = Objects.requireNonNull(builder.breaker, "breaker is required");
        this.from = Objects.requireNonNull(builder.from, "from is required");
        this.to = Objects.requireNonNull(builder.to, "to is required");
        if ((to == State.OPEN) != (builder.openDuration != null)) {
            throw new IllegalStateException("an open duration is required for and only for State.OPEN");
        }
        this.openDuration = builder.openDuration;
        this.failure = builder.failure;
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
     * Returns which breaker changed.
     *
     * @return the breaker
     */
    public Breaker getBreaker() {
        return breaker;
    }

    /**
     * Returns the previous state.
     *
     * @return the state before the transition
     */
    public State getFrom() {
        return from;
    }

    /**
     * Returns the new state.
     *
     * @return the state after the transition
     */
    public State getTo() {
        return to;
    }

    /**
     * Returns how long the breaker stays open before it lets a probe through.
     *
     * @return the duration, present only when the breaker opened
     */
    public Optional<Duration> getOpenDuration() {
        return Optional.ofNullable(openDuration);
    }

    /**
     * Returns the failure that opened the breaker.
     *
     * @return the last failure, or empty when the breaker closed
     */
    public Optional<Throwable> getFailure() {
        return Optional.ofNullable(failure);
    }

    /**
     * Returns when the transition happened, on the wall clock.
     *
     * @return the time
     */
    public Instant getTime() {
        return time;
    }

    @Override
    public String toString() {
        return "BreakerTransition{breaker=" + breaker + ", from=" + from + ", to=" + to
                + (openDuration != null ? ", openDuration=" + openDuration.toMillis() + "ms" : "") + "}";
    }

    /**
     * Builder for {@link BreakerTransition}. Not thread-safe.
     */
    public static final class Builder {

        private Breaker breaker;
        private State from;
        private State to;
        private Duration openDuration;
        private Throwable failure;
        private Instant time;

        private Builder() {
        }

        /**
         * Sets which breaker changed.
         *
         * @param breaker the breaker
         * @return this builder
         */
        public Builder breaker(Breaker breaker) {
            this.breaker = Objects.requireNonNull(breaker, "breaker must not be null");
            return this;
        }

        /**
         * Sets the previous state.
         *
         * @param from the state
         * @return this builder
         */
        public Builder from(State from) {
            this.from = Objects.requireNonNull(from, "from must not be null");
            return this;
        }

        /**
         * Sets the new state.
         *
         * @param to the state
         * @return this builder
         */
        public Builder to(State to) {
            this.to = Objects.requireNonNull(to, "to must not be null");
            return this;
        }

        /**
         * Sets how long the breaker stays open.
         *
         * @param openDuration the duration, required when the breaker opened
         * @return this builder
         */
        public Builder openDuration(Duration openDuration) {
            this.openDuration = Objects.requireNonNull(openDuration, "openDuration must not be null");
            return this;
        }

        /**
         * Sets the failure that opened the breaker.
         *
         * @param failure the failure, or {@code null} for none
         * @return this builder
         */
        public Builder failure(Throwable failure) {
            this.failure = failure;
            return this;
        }

        /**
         * Sets when the transition happened.
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
         * @throws NullPointerException  if a required value is missing
         * @throws IllegalStateException unless an open duration is set exactly when the breaker opened
         */
        public BreakerTransition build() {
            return new BreakerTransition(this);
        }
    }
}
