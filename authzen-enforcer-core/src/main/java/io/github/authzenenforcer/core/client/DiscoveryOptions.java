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
 * {@link #getDefaultTtl() defaultTtl}. If {@link #isHonorCacheHeaders() honorCacheHeaders} is set,
 * which is the default, the caching headers of a metadata response decide instead, within
 * {@link #getMinTtl() minTtl} and {@link #getMaxTtl() maxTtl}:
 * <ul>
 *   <li>{@code Cache-Control: max-age}, otherwise {@code Expires} minus {@code Date}, minus
 *       {@code Age}, sets how long the result is kept;</li>
 *   <li>{@code no-store}, an unqualified {@code no-cache}, {@code max-age=0} and any invalid value
 *       keep it for {@code minTtl};</li>
 *   <li>{@code no-store}, an unqualified {@code no-cache}, {@code must-revalidate} and
 *       {@code proxy-revalidate} forbid using it once it has expired (RFC 9111 section 4.2.4), and
 *       {@code stale-if-error=N} (RFC 5861) shortens {@code maxStale} to {@code N} seconds.</li>
 * </ul>
 * {@code s-maxage} is for shared caches and ignored, and so are the headers of a {@code 404}.
 *
 * <p>When a refresh after the expiry cannot reach the PDP, gets a server error or is rate-limited,
 * the previous result stays in use for at most {@link #getMaxStale() maxStale} past its expiry,
 * and the refresh is tried again every {@code minTtl}. Once {@code maxStale} has passed, the previous result is dropped and
 * requests fail, so that they deny, until a refresh succeeds. Any other failure, such as invalid
 * metadata, drops the previous result at once. A {@code 404} that replaces published
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

    /** Default longest time a result is kept, whatever its caching headers say. */
    public static final Duration DEFAULT_MAX_TTL = Duration.ofHours(24);

    /** Shortest accepted {@code minTtl}, so that a failing PDP is not asked for metadata on every request. */
    public static final Duration MIN_TTL_FLOOR = Duration.ofSeconds(1);

    /** Longest accepted duration for any of the options. */
    public static final Duration MAX_DURATION = Duration.ofDays(365);

    private static final DiscoveryOptions DEFAULTS = builder().build();

    private final Duration defaultTtl;
    private final Duration minTtl;
    private final Duration maxStale;
    private final Duration maxTtl;
    private final boolean honorCacheHeaders;

    private DiscoveryOptions(Builder builder) {
        if (builder.minTtl.compareTo(builder.defaultTtl) > 0) {
            throw new IllegalStateException("minTtl must not be longer than defaultTtl");
        }
        // maxTtl only bounds lifetimes from caching headers
        if (builder.honorCacheHeaders && builder.defaultTtl.compareTo(builder.maxTtl) > 0) {
            throw new IllegalStateException("defaultTtl must not be longer than maxTtl");
        }
        this.defaultTtl = builder.defaultTtl;
        this.minTtl = builder.minTtl;
        this.maxStale = builder.maxStale;
        this.maxTtl = builder.maxTtl;
        this.honorCacheHeaders = builder.honorCacheHeaders;
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
     * @return a duration of at least {@link #MIN_TTL_FLOOR}, not longer than {@link #getDefaultTtl()}
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

    /**
     * Returns the longest time a result is kept, whatever its caching headers say.
     *
     * @return a duration, not shorter than {@link #getDefaultTtl()} while caching headers are honoured
     */
    public Duration getMaxTtl() {
        return maxTtl;
    }

    /**
     * Tells whether the caching headers of metadata responses decide how long a result is kept.
     *
     * @return {@code true} by default
     */
    public boolean isHonorCacheHeaders() {
        return honorCacheHeaders;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DiscoveryOptions other)) {
            return false;
        }
        return defaultTtl.equals(other.defaultTtl) && minTtl.equals(other.minTtl) && maxStale.equals(other.maxStale)
                && maxTtl.equals(other.maxTtl) && honorCacheHeaders == other.honorCacheHeaders;
    }

    @Override
    public int hashCode() {
        return Objects.hash(defaultTtl, minTtl, maxStale, maxTtl, honorCacheHeaders);
    }

    @Override
    public String toString() {
        return "DiscoveryOptions{defaultTtl=" + defaultTtl + ", minTtl=" + minTtl + ", maxStale=" + maxStale
                + ", maxTtl=" + maxTtl + ", honorCacheHeaders=" + honorCacheHeaders + "}";
    }

    /**
     * Builder for {@link DiscoveryOptions}, initialized with the defaults. Not thread-safe.
     */
    public static final class Builder {

        private Duration defaultTtl = DEFAULT_TTL;
        private Duration minTtl = DEFAULT_MIN_TTL;
        private Duration maxStale = DEFAULT_MAX_STALE;
        private Duration maxTtl = DEFAULT_MAX_TTL;
        private boolean honorCacheHeaders = true;

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
         * @param minTtl a duration between {@link #MIN_TTL_FLOOR} and {@link #MAX_DURATION}
         * @return this builder
         */
        public Builder minTtl(Duration minTtl) {
            check(minTtl, "minTtl", false);
            if (minTtl.compareTo(MIN_TTL_FLOOR) < 0) {
                throw new IllegalArgumentException("minTtl must be at least " + MIN_TTL_FLOOR);
            }
            this.minTtl = minTtl;
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
         * Sets the longest time a result is kept, whatever its caching headers say. Defaults to
         * {@link #DEFAULT_MAX_TTL}.
         *
         * @param maxTtl a positive duration of at most {@link #MAX_DURATION}
         * @return this builder
         */
        public Builder maxTtl(Duration maxTtl) {
            this.maxTtl = check(maxTtl, "maxTtl", false);
            return this;
        }

        /**
         * Sets whether the caching headers of metadata responses decide how long a result is kept.
         * Enabled by default; when disabled, every result is kept for {@code defaultTtl}.
         *
         * @param honorCacheHeaders whether to follow the caching headers
         * @return this builder
         */
        public Builder honorCacheHeaders(boolean honorCacheHeaders) {
            this.honorCacheHeaders = honorCacheHeaders;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalStateException if {@code minTtl} is longer than {@code defaultTtl}, or
         *                               {@code defaultTtl} longer than {@code maxTtl} while caching
         *                               headers are honoured
         */
        public DiscoveryOptions build() {
            return new DiscoveryOptions(this);
        }

        private static Duration check(Duration duration, String name, boolean zeroAllowed) {
            Objects.requireNonNull(duration, () -> name + " must not be null");
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
