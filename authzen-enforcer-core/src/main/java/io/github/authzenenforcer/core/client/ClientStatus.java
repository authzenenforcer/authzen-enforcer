package io.github.authzenenforcer.core.client;

import io.github.authzenenforcer.core.event.BreakerTransition;
import io.github.authzenenforcer.core.event.MetadataRefresh;

import java.util.Objects;
import java.util.Optional;

/**
 * The state of a client as {@link AuthZenClient#status()} sees it, for example for a health check:
 * its circuit breakers and its last metadata refresh. Taking it does not contact the PDP.
 *
 * <p>Calls fail at once, and so deny, while the breaker of the authorization API is
 * {@linkplain BreakerTransition.State#OPEN open}, and calls that need an endpoint from the metadata
 * fail while the last refresh {@linkplain MetadataRefresh.Result#FAILED failed}, until a later one
 * succeeds.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class ClientStatus {

    private final BreakerTransition.State authorizationApiBreaker;
    private final BreakerTransition.State metadataDiscoveryBreaker;
    private final MetadataRefresh lastMetadataRefresh;

    private ClientStatus(Builder builder) {
        this.authorizationApiBreaker = Objects.requireNonNull(builder.authorizationApiBreaker,
                "authorizationApiBreaker must not be null");
        this.metadataDiscoveryBreaker = builder.metadataDiscoveryBreaker;
        this.lastMetadataRefresh = builder.lastMetadataRefresh;
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
     * Returns the state of the breaker shared by evaluations, batch evaluations and searches. An
     * open breaker whose interval has passed lets the next call through as a probe, so it is
     * reported as half-open. While a probe runs, the breaker stays half-open and the other calls
     * fail at once until the probe completes.
     *
     * @return the state
     */
    public BreakerTransition.State getAuthorizationApiBreaker() {
        return authorizationApiBreaker;
    }

    /**
     * Returns the state of the breaker of the metadata request, in the same way as
     * {@link #getAuthorizationApiBreaker()}.
     *
     * @return the state, or empty if discovery is disabled
     */
    public Optional<BreakerTransition.State> getMetadataDiscoveryBreaker() {
        return Optional.ofNullable(metadataDiscoveryBreaker);
    }

    /**
     * Returns the last metadata refresh, whether it succeeded or not, as it was reported to the
     * listeners. Its failure keeps its whole cause chain, so export it by class name unless its
     * message is known to be safe.
     *
     * @return the refresh, or empty if discovery is disabled or no refresh has completed yet
     */
    public Optional<MetadataRefresh> getLastMetadataRefresh() {
        return Optional.ofNullable(lastMetadataRefresh);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof ClientStatus other
                && authorizationApiBreaker == other.authorizationApiBreaker
                && metadataDiscoveryBreaker == other.metadataDiscoveryBreaker
                && Objects.equals(lastMetadataRefresh, other.lastMetadataRefresh);
    }

    @Override
    public int hashCode() {
        return Objects.hash(authorizationApiBreaker, metadataDiscoveryBreaker, lastMetadataRefresh);
    }

    @Override
    public String toString() {
        return "ClientStatus{authorizationApiBreaker=" + authorizationApiBreaker
                + ", metadataDiscoveryBreaker=" + metadataDiscoveryBreaker
                + ", lastMetadataRefresh=" + lastMetadataRefresh + '}';
    }

    /** Builds a {@link ClientStatus}; not thread-safe. */
    public static final class Builder {

        private BreakerTransition.State authorizationApiBreaker;
        private BreakerTransition.State metadataDiscoveryBreaker;
        private MetadataRefresh lastMetadataRefresh;

        private Builder() {
        }

        /**
         * Sets the state of the breaker of the authorization API; required.
         *
         * @param state the state
         * @return this builder
         */
        public Builder authorizationApiBreaker(BreakerTransition.State state) {
            this.authorizationApiBreaker = Objects.requireNonNull(state, "state must not be null");
            return this;
        }

        /**
         * Sets the state of the breaker of the metadata request; leave it unset if discovery is
         * disabled.
         *
         * @param state the state
         * @return this builder
         */
        public Builder metadataDiscoveryBreaker(BreakerTransition.State state) {
            this.metadataDiscoveryBreaker = Objects.requireNonNull(state, "state must not be null");
            return this;
        }

        /**
         * Sets the last metadata refresh; leave it unset if none has completed.
         *
         * @param refresh the refresh
         * @return this builder
         */
        public Builder lastMetadataRefresh(MetadataRefresh refresh) {
            this.lastMetadataRefresh = Objects.requireNonNull(refresh, "refresh must not be null");
            return this;
        }

        /**
         * Builds the status.
         *
         * @return the status
         * @throws NullPointerException if the state of the authorization API breaker is not set
         */
        public ClientStatus build() {
            return new ClientStatus(this);
        }
    }
}
