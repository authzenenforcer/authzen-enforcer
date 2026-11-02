package io.github.authzenenforcer.core.client;

import java.time.Duration;
import java.util.Objects;

/**
 * How the client caches the metadata it discovers from the PDP.
 *
 * <pre>{@code
 * AuthZenClient client = AuthZenClient.builder()
 *         .pdpUrl("https://pdp.example.com")
 *         .discovery(DiscoveryOptions.builder()
 *                 .defaultTtl(Duration.ofMinutes(30))
 *                 .maxStale(Duration.ofMinutes(15))
 *                 .build())
 *         .build();
 * }</pre>
 *
 * <p>A result, including a {@code 404} that means the PDP publishes no metadata, is kept for
 * {@link #getDefaultTtl() defaultTtl}. When a refresh after that cannot reach the PDP, gets a server
 * error or is rate-limited, the previous result stays in use for at most
 * {@link #getMaxStale() maxStale} past its expiry, and the refresh is tried again every
 * {@link #getMinTtl() minTtl}. Once {@code maxStale} has passed, the previous result is dropped and
 * requests fail, so that they deny, until a refresh succeeds. A {@code 404} that replaces published
 * metadata is also kept only for {@code minTtl}, since it may come from a proxy during a rollout.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @see AuthZenClient.Builder#discovery(DiscoveryOptions)
 */
public final class DiscoveryOptions {

    /** Default time a result is kept before it is fetched again. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);

    /** Default shortest time a result is kept, also the wait before a failed refresh is tried again. */
    public static final Duration DEFAULT_MIN_TTL = Duration.ofMinutes(1);

    /** Default time a previous result may still be used after its expiry while refreshes fail. */
    public static final Duration DEFAULT_MAX_STALE = Duration.ofHours(1);

    /** Longest accepted duration for any of the options. */
    public static final Duration MAX_DURATION = Duration.ofDays(365);

    private static final DiscoveryOptions DEFAULTS = builder().build();

    private final Duration defaultTtl;
    private final Duration minTtl;
    private final Duration maxStale;

    private DiscoveryOptions(Builder builder) {
        if (builder.minTtl.compareTo(builder.defaultTtl) > 0) {
            throw new IllegalStateException("minTtl must not be longer than defaultTtl");
        }
        this.defaultTtl = builder.defaultTtl;
        this.minTtl = builder.minTtl;
        this.maxStale = builder.maxStale;
    }

    /**
     * Returns the options with every default.
     *
     * @return the default options
     */
    public static DiscoveryOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a new builder, initialized with the defaults.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns how long a result is kept before it is fetched again.
     *
     * @return a positive duration
     */
    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    /**
     * Returns the shortest time a result is kept. It is also the wait before a failed refresh is
     * tried again while a previous result stays in use.
     *
     * @return a positive duration, not longer than {@link #getDefaultTtl()}
     */
    public Duration getMinTtl() {
        return minTtl;
    }

    /**
     * Returns how long a previous result may still be used after its expiry while refreshes cannot
     * reach the PDP.
     *
     * @return a duration, zero if a previous result is never used after its expiry
     */
    public Duration getMaxStale() {
        return maxStale;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DiscoveryOptions other)) {
            return false;
        }
        return defaultTtl.equals(other.defaultTtl) && minTtl.equals(other.minTtl) && maxStale.equals(other.maxStale);
    }

    @Override
    public int hashCode() {
        return Objects.hash(defaultTtl, minTtl, maxStale);
    }

    @Override
    public String toString() {
        return "DiscoveryOptions{defaultTtl=" + defaultTtl + ", minTtl=" + minTtl + ", maxStale=" + maxStale + "}";
    }

    /**
     * Builder for {@link DiscoveryOptions}, initialized with the defaults. Not thread-safe.
     */
    public static final class Builder {

        private Duration defaultTtl = DEFAULT_TTL;
        private Duration minTtl = DEFAULT_MIN_TTL;
        private Duration maxStale = DEFAULT_MAX_STALE;

        private Builder() {
        }

        /**
         * Sets how long a result is kept before it is fetched again. Defaults to {@link #DEFAULT_TTL}.
         *
         * @param defaultTtl a positive duration of at most {@link #MAX_DURATION}
         * @return this builder
         */
        public Builder defaultTtl(Duration defaultTtl) {
            this.defaultTtl = check(defaultTtl, "defaultTtl", false);
            return this;
        }

        /**
         * Sets the shortest time a result is kept, also the wait before a failed refresh is tried
         * again. Defaults to {@link #DEFAULT_MIN_TTL}.
         *
         * @param minTtl a positive duration of at most {@link #MAX_DURATION}
         * @return this builder
         */
        public Builder minTtl(Duration minTtl) {
            this.minTtl = check(minTtl, "minTtl", false);
            return this;
        }

        /**
         * Sets how long a previous result may still be used after its expiry while refreshes cannot
         * reach the PDP. Defaults to {@link #DEFAULT_MAX_STALE}.
         *
         * @param maxStale a duration of at most {@link #MAX_DURATION}; zero never uses an expired result
         * @return this builder
         */
        public Builder maxStale(Duration maxStale) {
            this.maxStale = check(maxStale, "maxStale", true);
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalStateException if {@code minTtl} is longer than {@code defaultTtl}
         */
        public DiscoveryOptions build() {
            return new DiscoveryOptions(this);
        }

        private static Duration check(Duration duration, String name, boolean zeroAllowed) {
            Objects.requireNonNull(duration, name);
            if (duration.isNegative() || (!zeroAllowed && duration.isZero())) {
                throw new IllegalArgumentException(name + (zeroAllowed ? " must not be negative" : " must be positive"));
            }
            if (duration.compareTo(MAX_DURATION) > 0) {
                throw new IllegalArgumentException(name + " must not be longer than " + MAX_DURATION);
            }
            return duration;
        }
    }
}
