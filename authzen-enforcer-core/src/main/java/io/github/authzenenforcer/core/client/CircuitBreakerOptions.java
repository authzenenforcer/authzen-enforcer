package io.github.authzenenforcer.core.client;

import java.time.Duration;
import java.util.Objects;

/**
 * How the client stops calling a PDP that keeps failing.
 *
 * <p>The client keeps one circuit breaker for metadata discovery and one for the Authorization API:
 * evaluations, batch evaluations and searches share it. After
 * {@link #getFailureThreshold() failureThreshold} failures in a row, a breaker opens: requests fail
 * at once with a {@link io.github.authzenenforcer.core.PdpCommunicationException}, so that they deny,
 * without contacting the PDP. The exception's cause is the last real failure. After the open
 * duration one request is let through as a probe. If it succeeds, the breaker closes; if it fails,
 * the breaker opens again for twice as long, up to {@link #getMaxOpenDuration() maxOpenDuration}.
 * Each open duration is randomized between half and all of its nominal value, so that many clients
 * do not probe a recovering PDP at the same moment.
 *
 * <p>For the Authorization API, only communication failures count: an unreachable PDP, a timeout, a server
 * error ({@code 5xx}) or a lasting {@code 429}. Any other answer shows that the PDP is up and closes
 * the breaker. For discovery, every failure counts, including invalid metadata and
 * {@code 401}/{@code 403}, since asking again does not help. While the discovery breaker is open,
 * previously discovered metadata is still used within {@link DiscoveryOptions#getMaxStale()}.
 *
 * <p>A probe that hangs, for example in a {@link io.github.authzenenforcer.core.auth.CredentialProvider}
 * that blocks, keeps the breaker from letting other requests through until it returns. The
 * request timeout bounds a probe only once its request has been sent.
 *
 * <pre>{@code
 * AuthZenClient client = AuthZenClient.builder()
 *         .pdpUrl("https://pdp.example.com")
 *         .circuitBreaker(CircuitBreakerOptions.builder()
 *                 .failureThreshold(5)
 *                 .maxOpenDuration(Duration.ofMinutes(1))
 *                 .build())
 *         .build();
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @see AuthZenClient.Builder#circuitBreaker(CircuitBreakerOptions)
 */
public final class CircuitBreakerOptions {

    /** Default number of failures in a row that opens a breaker. */
    public static final int DEFAULT_FAILURE_THRESHOLD = 3;

    /** Highest accepted failure threshold. */
    public static final int MAX_FAILURE_THRESHOLD = 100;

    /** Default nominal duration a breaker stays open the first time. */
    public static final Duration DEFAULT_OPEN_DURATION = Duration.ofSeconds(5);

    /** Default longest nominal duration a breaker stays open. */
    public static final Duration DEFAULT_MAX_OPEN_DURATION = Duration.ofMinutes(5);

    /** Shortest accepted open duration. */
    public static final Duration MIN_OPEN_DURATION = Duration.ofMillis(100);

    /** Longest accepted open duration. */
    public static final Duration MAX_OPEN_DURATION = Duration.ofDays(1);

    private static final CircuitBreakerOptions DEFAULTS = builder().build();
    private static final CircuitBreakerOptions DISABLED = new CircuitBreakerOptions();

    private final boolean enabled;
    private final int failureThreshold;
    private final Duration openDuration;
    private final Duration maxOpenDuration;

    private CircuitBreakerOptions(Builder builder) {
        if (builder.openDuration.compareTo(builder.maxOpenDuration) > 0) {
            throw new IllegalStateException("openDuration must not be longer than maxOpenDuration");
        }
        this.enabled = true;
        this.failureThreshold = builder.failureThreshold;
        this.openDuration = builder.openDuration;
        this.maxOpenDuration = builder.maxOpenDuration;
    }

    private CircuitBreakerOptions() {
        this.enabled = false;
        this.failureThreshold = DEFAULT_FAILURE_THRESHOLD;
        this.openDuration = DEFAULT_OPEN_DURATION;
        this.maxOpenDuration = DEFAULT_MAX_OPEN_DURATION;
    }

    /**
     * Returns enabled breakers with every default.
     *
     * @return the default options
     */
    public static CircuitBreakerOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options that never open a breaker: every request contacts the PDP.
     *
     * @return the disabled options
     */
    public static CircuitBreakerOptions disabled() {
        return DISABLED;
    }

    /**
     * Returns a new builder for enabled breakers, initialized with the defaults.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Tells whether breakers are used at all.
     *
     * @return {@code false} for {@link #disabled()}
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the number of failures in a row that opens a breaker.
     *
     * @return a number between 1 and {@link #MAX_FAILURE_THRESHOLD}
     */
    public int getFailureThreshold() {
        return failureThreshold;
    }

    /**
     * Returns the nominal duration a breaker stays open the first time.
     *
     * @return a duration between {@link #MIN_OPEN_DURATION} and {@link #getMaxOpenDuration()}
     */
    public Duration getOpenDuration() {
        return openDuration;
    }

    /**
     * Returns the longest nominal duration a breaker stays open after repeated failed probes.
     *
     * @return a duration of at most {@link #MAX_OPEN_DURATION}
     */
    public Duration getMaxOpenDuration() {
        return maxOpenDuration;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CircuitBreakerOptions other)) {
            return false;
        }
        return enabled == other.enabled && failureThreshold == other.failureThreshold
                && openDuration.equals(other.openDuration) && maxOpenDuration.equals(other.maxOpenDuration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, failureThreshold, openDuration, maxOpenDuration);
    }

    @Override
    public String toString() {
        if (!enabled) {
            return "CircuitBreakerOptions{disabled}";
        }
        return "CircuitBreakerOptions{failureThreshold=" + failureThreshold + ", openDuration=" + openDuration
                + ", maxOpenDuration=" + maxOpenDuration + "}";
    }

    /**
     * Builder for enabled {@link CircuitBreakerOptions}, initialized with the defaults. Not thread-safe.
     */
    public static final class Builder {

        private int failureThreshold = DEFAULT_FAILURE_THRESHOLD;
        private Duration openDuration = DEFAULT_OPEN_DURATION;
        private Duration maxOpenDuration = DEFAULT_MAX_OPEN_DURATION;

        private Builder() {
        }

        /**
         * Sets the number of failures in a row that opens a breaker. Defaults to
         * {@link #DEFAULT_FAILURE_THRESHOLD}.
         *
         * @param failureThreshold a number between 1 and {@link #MAX_FAILURE_THRESHOLD}
         * @return this builder
         */
        public Builder failureThreshold(int failureThreshold) {
            if (failureThreshold < 1 || failureThreshold > MAX_FAILURE_THRESHOLD) {
                throw new IllegalArgumentException("failureThreshold must be between 1 and " + MAX_FAILURE_THRESHOLD);
            }
            this.failureThreshold = failureThreshold;
            return this;
        }

        /**
         * Sets the nominal duration a breaker stays open the first time. Defaults to
         * {@link #DEFAULT_OPEN_DURATION}.
         *
         * @param openDuration a duration between {@link #MIN_OPEN_DURATION} and {@link #MAX_OPEN_DURATION}
         * @return this builder
         */
        public Builder openDuration(Duration openDuration) {
            this.openDuration = check(openDuration, "openDuration");
            return this;
        }

        /**
         * Sets the longest nominal duration a breaker stays open. Defaults to
         * {@link #DEFAULT_MAX_OPEN_DURATION}.
         *
         * @param maxOpenDuration a duration between {@link #MIN_OPEN_DURATION} and {@link #MAX_OPEN_DURATION}
         * @return this builder
         */
        public Builder maxOpenDuration(Duration maxOpenDuration) {
            this.maxOpenDuration = check(maxOpenDuration, "maxOpenDuration");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalStateException if {@code openDuration} is longer than {@code maxOpenDuration}
         */
        public CircuitBreakerOptions build() {
            return new CircuitBreakerOptions(this);
        }

        private static Duration check(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.compareTo(MIN_OPEN_DURATION) < 0 || duration.compareTo(MAX_OPEN_DURATION) > 0) {
                throw new IllegalArgumentException(name + " must be between " + MIN_OPEN_DURATION + " and "
                        + MAX_OPEN_DURATION);
            }
            return duration;
        }
    }
}
