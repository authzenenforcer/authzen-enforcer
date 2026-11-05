package io.github.authzenenforcer.core.event;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.model.PdpMetadata;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A completed request for the PDP metadata.
 *
 * <p>Calls answered from the cache report nothing; neither do calls rejected while the discovery
 * breaker is open, which a {@link BreakerTransition} reports instead.
 *
 * <p>Instances are immutable and thread-safe. The SDK creates them; the builder lets tests of a
 * listener create their own.
 */
public final class MetadataRefresh {

    /** What the client uses after the request. */
    public enum Result {

        /** Metadata the PDP just published. */
        FRESH,

        /**
         * The previous result, because the PDP could not be reached: the previous metadata, or
         * the default paths if the PDP published none. It is used for at most
         * {@link io.github.authzenenforcer.core.client.DiscoveryOptions#getMaxStale() maxStale}.
         */
        STALE,

        /** The default paths, because the PDP publishes no metadata. */
        DEFAULT_PATHS,

        /** Nothing: calls that need an endpoint fail, and so deny, until a refresh succeeds. */
        FAILED
    }

    private final Result result;
    private final URI location;
    private final PdpMetadata metadata;
    private final AuthZenException failure;
    private final Instant time;

    private MetadataRefresh(Builder builder) {
        this.result = Objects.requireNonNull(builder.result, "result is required");
        this.location = Objects.requireNonNull(builder.location, "location is required");
        if (result == Result.FRESH && builder.metadata == null) {
            throw new IllegalStateException("metadata is required for FRESH");
        }
        if ((result == Result.DEFAULT_PATHS || result == Result.FAILED) && builder.metadata != null) {
            throw new IllegalStateException("metadata is not allowed for DEFAULT_PATHS and FAILED");
        }
        boolean failed = result == Result.STALE || result == Result.FAILED;
        if (failed != (builder.failure != null)) {
            throw new IllegalStateException("a failure is required for and only for STALE and FAILED");
        }
        this.metadata = builder.metadata;
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
     * Returns what the client uses after the request.
     *
     * @return the result
     */
    public Result getResult() {
        return result;
    }

    /**
     * Returns the URL of the metadata.
     *
     * @return the well-known location below the PDP URL
     */
    public URI getLocation() {
        return location;
    }

    /**
     * Returns the metadata in use.
     *
     * @return the metadata for {@link Result#FRESH}, and for {@link Result#STALE} unless the
     *         previous result was the default paths; otherwise empty
     */
    public Optional<PdpMetadata> getMetadata() {
        return Optional.ofNullable(metadata);
    }

    /**
     * Returns why the refresh failed.
     *
     * @return the failure for {@link Result#STALE} and {@link Result#FAILED}, otherwise empty
     */
    public Optional<AuthZenException> getFailure() {
        return Optional.ofNullable(failure);
    }

    /**
     * Returns when the request completed, on the wall clock.
     *
     * @return the time
     */
    public Instant getTime() {
        return time;
    }

    @Override
    public String toString() {
        return "MetadataRefresh{result=" + result + ", location=" + location
                + (failure != null ? ", failure=" + failure.getClass().getSimpleName() : "") + "}";
    }

    /**
     * Builder for {@link MetadataRefresh}. Not thread-safe.
     */
    public static final class Builder {

        private Result result;
        private URI location;
        private PdpMetadata metadata;
        private AuthZenException failure;
        private Instant time;

        private Builder() {
        }

        /**
         * Sets what the client uses after the request.
         *
         * @param result the result
         * @return this builder
         */
        public Builder result(Result result) {
            this.result = Objects.requireNonNull(result, "result must not be null");
            return this;
        }

        /**
         * Sets the URL of the metadata.
         *
         * @param location the location
         * @return this builder
         */
        public Builder location(URI location) {
            this.location = Objects.requireNonNull(location, "location must not be null");
            return this;
        }

        /**
         * Sets the metadata in use.
         *
         * @param metadata the metadata, required for {@link Result#FRESH}; for {@link Result#STALE}
         *                 only if the previous result was metadata
         * @return this builder
         */
        public Builder metadata(PdpMetadata metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
            return this;
        }

        /**
         * Sets why the refresh failed.
         *
         * @param failure the failure, required for {@link Result#STALE} and {@link Result#FAILED}
         * @return this builder
         */
        public Builder failure(AuthZenException failure) {
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
            return this;
        }

        /**
         * Sets when the request completed.
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
         * @throws IllegalStateException if metadata or failure do not match the result
         */
        public MetadataRefresh build() {
            return new MetadataRefresh(this);
        }
    }
}
