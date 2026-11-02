package io.github.authzenenforcer.core.internal.client;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.client.DiscoveryOptions;
import io.github.authzenenforcer.core.internal.http.PdpTransport;
import io.github.authzenenforcer.core.internal.text.LogText;
import io.github.authzenenforcer.core.internal.wire.WireFormat;
import io.github.authzenenforcer.core.model.PdpMetadata;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Discovers and caches the metadata of a PDP.
 *
 * <p>The metadata is fetched on first use; concurrent callers share one request. It is kept for
 * {@link DiscoveryOptions#getDefaultTtl() defaultTtl} and fetched again on the next use after that.
 * A PDP that answers {@code 404} publishes no metadata, which is cached the same way, except that a
 * {@code 404} replacing published metadata is kept only for {@link DiscoveryOptions#getMinTtl()
 * minTtl}. If a refresh fails with a communication error, which includes a PDP that keeps
 * rate-limiting, the previous result stays in use until {@link DiscoveryOptions#getMaxStale()
 * maxStale} after its expiry, and the refresh is tried again every {@code minTtl}. After that, and
 * for any other failure, the caller fails, so that it denies.
 *
 * <p>Metadata whose {@code policy_decision_point} differs from the identifier is rejected, as the
 * specification requires, and so are endpoints that are not usable URLs or that use plain HTTP
 * for a PDP identified by an {@code https} URL.
 *
 * <p>Instances are thread-safe.
 */
final class MetadataResolver {

    /** Well-known path of the PDP metadata. */
    static final String WELL_KNOWN_PATH = "/.well-known/authzen-configuration";

    private static final System.Logger LOGGER = System.getLogger(MetadataResolver.class.getName());

    private final PdpTransport transport;
    private final URI identifier;
    private final URI location;
    private final long ttlNanos;
    private final long minTtlNanos;
    private final long maxStaleNanos;
    private final boolean sendsCredentials;
    private final LongSupplier clock;
    // Not synchronized: on Java 21 to 23 a virtual thread waiting for a monitor pins its carrier
    private final ReentrantLock lock = new ReentrantLock();

    // Guarded by lock
    private Cached cached;
    private CompletableFuture<Optional<PdpMetadata>> pending;

    /**
     * Creates a resolver.
     *
     * @param transport        the transport
     * @param identifier       the PDP identifier, without terminating slashes
     * @param options          how results are cached
     * @param sendsCredentials whether requests to the endpoints carry credentials
     * @param clock            a monotonic clock in nanoseconds, such as {@link System#nanoTime()}
     */
    MetadataResolver(PdpTransport transport, URI identifier, DiscoveryOptions options, boolean sendsCredentials,
            LongSupplier clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.location = Urls.wellKnown(identifier, WELL_KNOWN_PATH);
        // DiscoveryOptions caps each duration at a year, so these sums cannot overflow
        this.ttlNanos = options.getDefaultTtl().toNanos();
        this.minTtlNanos = options.getMinTtl().toNanos();
        this.maxStaleNanos = options.getMaxStale().toNanos();
        this.sendsCredentials = sendsCredentials;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the metadata, fetching it if there is no current result.
     *
     * @return a future completed with the metadata, empty if the PDP publishes none, or
     *         exceptionally with an {@link AuthZenException}
     */
    CompletableFuture<Optional<PdpMetadata>> metadata() {
        CompletableFuture<Optional<PdpMetadata>> result;
        lock.lock();
        try {
            if (cached != null && clock.getAsLong() - cached.refreshAtNanos < 0) {
                return CompletableFuture.completedFuture(cached.metadata);
            }
            if (pending != null) {
                // A copy, so that a caller cancelling its future does not fail the others
                return pending.copy();
            }
            result = new CompletableFuture<>();
            pending = result;
        } finally {
            lock.unlock();
        }
        // Started outside the lock, since the transport may complete synchronously
        CompletableFuture<Optional<PdpMetadata>> fetched;
        try {
            fetched = transport.get(location).thenApply(this::accept);
        } catch (RuntimeException e) {
            fetched = CompletableFuture.failedFuture(e);
        }
        fetched.whenComplete((metadata, error) -> settle(result, metadata, error));
        return result.copy();
    }

    private Optional<PdpMetadata> accept(Optional<Object> document) {
        if (document.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(verify(WireFormat.pdpMetadata(document.get()), identifier, location));
    }

    /**
     * Checks that metadata belongs to the PDP and that its endpoints can be used.
     *
     * @param metadata   the metadata as received
     * @param identifier the PDP identifier the metadata was fetched for
     * @param location   the URL the metadata was fetched from, for error messages
     * @return the metadata
     * @throws PdpProtocolException if the metadata must not be used
     */
    static PdpMetadata verify(PdpMetadata metadata, URI identifier, URI location) {
        if (!metadata.getPolicyDecisionPoint().equals(identifier.toString())) {
            throw new PdpProtocolException("PDP metadata at " + location + " is for policy_decision_point "
                    + LogText.of(metadata.getPolicyDecisionPoint()) + ", not " + identifier);
        }
        check("access_evaluation_endpoint", Optional.of(metadata.getAccessEvaluationEndpoint()), identifier, location);
        check("access_evaluations_endpoint", metadata.getAccessEvaluationsEndpoint(), identifier, location);
        check("search_subject_endpoint", metadata.getSearchSubjectEndpoint(), identifier, location);
        check("search_resource_endpoint", metadata.getSearchResourceEndpoint(), identifier, location);
        check("search_action_endpoint", metadata.getSearchActionEndpoint(), identifier, location);
        return metadata;
    }

    private static void check(String name, Optional<URI> endpoint, URI identifier, URI location) {
        if (endpoint.isEmpty()) {
            return;
        }
        try {
            Urls.check(endpoint.get(), name);
        } catch (IllegalArgumentException e) {
            throw new PdpProtocolException("PDP metadata at " + location + ": " + e.getMessage(), e);
        }
        // Credentials must not lose the protection the PDP identifier promises
        if (Urls.isHttps(identifier) && !Urls.isHttps(endpoint.get())) {
            throw new PdpProtocolException("PDP metadata at " + location + ": " + name
                    + " uses plain HTTP, but the PDP is identified by an https URL");
        }
    }

    private void settle(CompletableFuture<Optional<PdpMetadata>> result, Optional<PdpMetadata> metadata,
            Throwable error) {
        Optional<PdpMetadata> value = null;
        AuthZenException failure = null;
        Optional<PdpMetadata> previous;
        boolean keptPrevious = false;
        boolean droppedStale = false;
        lock.lock();
        try {
            pending = null;
            previous = cached == null ? null : cached.metadata;
            long now = clock.getAsLong();
            if (error == null) {
                // A 404 right after published metadata may come from a proxy during a rollout, so
                // it is checked again soon instead of pinning the default paths for a full interval
                boolean unpublished = metadata.isEmpty() && previous != null && previous.isPresent();
                long expiresAt = now + (unpublished ? minTtlNanos : ttlNanos);
                cached = new Cached(metadata, expiresAt, expiresAt + maxStaleNanos);
                value = metadata;
            } else {
                Throwable cause = unwrap(error);
                if (cause instanceof PdpCommunicationException && cached != null && now - cached.staleUntilNanos < 0) {
                    // Try again after minTtl, but never use the previous result past maxStale
                    long retryAt = now + minTtlNanos;
                    long refreshAt = retryAt - cached.staleUntilNanos < 0 ? retryAt : cached.staleUntilNanos;
                    cached = new Cached(cached.metadata, refreshAt, cached.staleUntilNanos);
                    value = cached.metadata;
                    keptPrevious = true;
                } else {
                    droppedStale = cached != null && cause instanceof PdpCommunicationException;
                    if (droppedStale) {
                        cached = null;
                    }
                    failure = cause instanceof AuthZenException authZenException ? authZenException
                            : new AuthZenException("PDP metadata discovery failed unexpectedly", cause);
                }
            }
        } finally {
            lock.unlock();
        }
        // Complete before logging, so that a failing logger cannot leave the waiting callers blocked
        if (failure != null) {
            result.completeExceptionally(failure);
            if (droppedStale) {
                LOGGER.log(Level.WARNING, "PDP metadata from " + location + " expired more than maxStale ago and "
                        + "could not be refreshed; requests fail until a refresh succeeds: " + failure.getMessage());
            } else {
                LOGGER.log(Level.DEBUG, () -> "PDP metadata discovery at " + location + " failed: " + error);
            }
            return;
        }
        result.complete(value);
        if (keptPrevious) {
            LOGGER.log(Level.WARNING, "Refreshing the PDP metadata from " + location
                    + " failed; keeping the previous result: " + unwrap(error).getMessage());
        } else if (!value.equals(previous)) {
            logChange(value);
        }
    }

    private void logChange(Optional<PdpMetadata> metadata) {
        if (metadata.isEmpty()) {
            LOGGER.log(Level.INFO, "PDP publishes no metadata at " + location + "; using the default paths below "
                    + identifier);
            return;
        }
        LOGGER.log(Level.DEBUG, () -> "PDP metadata from " + location + ": " + metadata.get());
        if (!sendsCredentials) {
            return;
        }
        PdpMetadata current = metadata.get();
        List<Optional<URI>> endpoints = List.of(Optional.of(current.getAccessEvaluationEndpoint()),
                current.getAccessEvaluationsEndpoint(), current.getSearchSubjectEndpoint(),
                current.getSearchResourceEndpoint(), current.getSearchActionEndpoint());
        endpoints.stream()
                .flatMap(Optional::stream)
                .filter(Urls::isPlainHttpToRemoteHost)
                .forEach(endpoint -> LOGGER.log(Level.WARNING, "PDP endpoint " + endpoint
                        + " uses plain HTTP; credentials are sent unencrypted"));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * A result with the time of the next fetch and the time after which it must no longer be used,
     * both on the resolver's clock.
     */
    private record Cached(Optional<PdpMetadata> metadata, long refreshAtNanos, long staleUntilNanos) {
    }
}
