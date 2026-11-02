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
import java.time.Duration;
import java.time.Instant;
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
 * the lifetime its caching headers give, within {@link DiscoveryOptions#getMinTtl() minTtl} and
 * {@link DiscoveryOptions#getMaxTtl() maxTtl}, or for {@link DiscoveryOptions#getDefaultTtl()
 * defaultTtl} without such headers (see {@link CacheDirectives}), and fetched again on the next use
 * after that. {@code must-revalidate} allows no use after the expiry, and {@code stale-if-error}
 * shortens {@code maxStale}.
 * A PDP that answers {@code 404} publishes no metadata, which is cached the same way, except that a
 * {@code 404} replacing published metadata is kept only for {@link DiscoveryOptions#getMinTtl()
 * minTtl}. If a refresh fails with a communication error, which includes a PDP that keeps
 * rate-limiting, the previous result stays in use until {@link DiscoveryOptions#getMaxStale()
 * maxStale} after its expiry, and the refresh is tried again every {@code minTtl}. After that, and
 * for any other failure, the previous result is dropped and the caller fails, so that it denies.
 *
 * <p>Every fetch goes through a {@link CircuitBreaker} that counts every kind of failure. While it
 * is open, no fetch starts: a previous result within {@code maxStale} is returned, otherwise the
 * caller fails at once.
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
    private final Duration minTtl;
    private final Duration maxTtl;
    private final Duration maxStale;
    private final boolean honorCacheHeaders;
    private final boolean sendsCredentials;
    private final LongSupplier clock;
    private final CircuitBreaker breaker;
    // Not synchronized: on Java 21 to 23 a virtual thread waiting for a monitor pins its carrier
    private final ReentrantLock lock = new ReentrantLock();

    // Guarded by lock
    private Cached cached;
    private CompletableFuture<Optional<PdpMetadata>> pending;
    private CircuitBreaker.Permit pendingPermit;
    // Whether the last result the PDP gave was published metadata; survives dropping the cache
    private boolean lastPublished;

    /**
     * Creates a resolver.
     *
     * @param transport        the transport
     * @param identifier       the PDP identifier, without terminating slashes
     * @param options          how results are cached
     * @param sendsCredentials whether requests to the endpoints carry credentials
     * @param clock            a monotonic clock in nanoseconds, such as {@link System#nanoTime()}
     * @param breaker          the breaker that suspends fetches after repeated failures, on the same clock
     */
    MetadataResolver(PdpTransport transport, URI identifier, DiscoveryOptions options, boolean sendsCredentials,
            LongSupplier clock, CircuitBreaker breaker) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.location = Urls.wellKnown(identifier, WELL_KNOWN_PATH);
        // Deadlines are sums on a clock with an arbitrary origin and may wrap around, so they are
        // only ever compared through differences. DiscoveryOptions caps each duration at a year,
        // which keeps every difference far below 2^63
        this.ttlNanos = options.getDefaultTtl().toNanos();
        this.minTtlNanos = options.getMinTtl().toNanos();
        this.maxStaleNanos = options.getMaxStale().toNanos();
        this.minTtl = options.getMinTtl();
        this.maxTtl = options.getMaxTtl();
        this.maxStale = options.getMaxStale();
        this.honorCacheHeaders = options.isHonorCacheHeaders();
        this.sendsCredentials = sendsCredentials;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.breaker = Objects.requireNonNull(breaker, "breaker");
    }

    /**
     * Returns the metadata, fetching it if there is no current result.
     *
     * @return a future completed with the metadata, empty if the PDP publishes none, or
     *         exceptionally with an {@link AuthZenException}
     */
    CompletableFuture<Optional<PdpMetadata>> metadata() {
        CompletableFuture<Optional<PdpMetadata>> result;
        long now;
        try {
            now = now();
        } catch (AuthZenException e) {
            return CompletableFuture.failedFuture(e);
        }
        lock.lock();
        try {
            if (cached != null && now - cached.refreshAtNanos < 0) {
                return CompletableFuture.completedFuture(cached.metadata);
            }
            if (pending != null) {
                // A copy, so that a caller cancelling its future does not fail the others
                return pending.copy();
            }
            CircuitBreaker.Permit permit;
            try {
                permit = breaker.acquire(now);
            } catch (PdpCommunicationException suspended) {
                // While fetches are suspended, a previous result is still good within maxStale
                if (cached != null && now - cached.staleUntilNanos < 0) {
                    return CompletableFuture.completedFuture(cached.metadata);
                }
                return CompletableFuture.failedFuture(suspended);
            }
            result = new CompletableFuture<>();
            pending = result;
            pendingPermit = permit;
        } finally {
            lock.unlock();
        }
        // Started outside the lock, since the transport may complete synchronously
        CompletableFuture<Fetched> fetched;
        try {
            fetched = transport.get(location).thenApply(this::accept);
        } catch (RuntimeException e) {
            fetched = CompletableFuture.failedFuture(e);
        }
        fetched.whenComplete((document, error) -> settle(result, document, error));
        return result.copy();
    }

    /** A verified fetch result and what its response says about caching it. */
    private record Fetched(Optional<PdpMetadata> metadata, CacheDirectives.Freshness freshness) {
    }

    private Fetched accept(PdpTransport.Document document) {
        if (document.body().isEmpty()) {
            return new Fetched(Optional.empty(), CacheDirectives.Freshness.NONE);
        }
        PdpMetadata metadata = verify(WireFormat.pdpMetadata(document.body().get()), identifier, location);
        CacheDirectives.Freshness freshness = honorCacheHeaders
                ? CacheDirectives.parse(document.headers(), Instant.now())
                : CacheDirectives.Freshness.NONE;
        return new Fetched(Optional.of(metadata), freshness);
    }

    /** How long a fresh result is kept: its own lifetime within [minTtl, maxTtl], or defaultTtl. */
    private long ttlNanos(Fetched fetched, boolean unpublished) {
        if (unpublished) {
            return minTtlNanos;
        }
        Optional<Duration> lifetime = fetched.freshness().lifetime();
        if (lifetime.isEmpty()) {
            return ttlNanos;
        }
        Duration bounded = lifetime.get().compareTo(minTtl) < 0 ? minTtl
                : lifetime.get().compareTo(maxTtl) > 0 ? maxTtl : lifetime.get();
        return bounded.toNanos();
    }

    /** How long a result may be used after its expiry while refreshes cannot reach the PDP. */
    private long staleNanos(Fetched fetched) {
        CacheDirectives.Freshness freshness = fetched.freshness();
        if (freshness.mustRevalidate()) {
            return 0;
        }
        return freshness.staleIfError()
                .map(allowed -> allowed.compareTo(maxStale) < 0 ? allowed.toNanos() : maxStaleNanos)
                .orElse(maxStaleNanos);
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

    private void settle(CompletableFuture<Optional<PdpMetadata>> result, Fetched fetched, Throwable error) {
        long now;
        try {
            now = now();
        } catch (AuthZenException e) {
            // Nothing changed yet; clear the fetch so that the next call tries again
            CircuitBreaker.Permit permit;
            lock.lock();
            try {
                pending = null;
                permit = pendingPermit;
            } finally {
                lock.unlock();
            }
            breaker.released(permit);
            result.completeExceptionally(e);
            return;
        }
        Optional<PdpMetadata> value = null;
        AuthZenException failure = null;
        Optional<PdpMetadata> previous = null;
        boolean keptPrevious = false;
        boolean droppedStale = false;
        boolean droppedUnusable = false;
        Runnable breakerLog = () -> { };
        lock.lock();
        try {
            pending = null;
            CircuitBreaker.Permit permit = pendingPermit;
            // Every failure counts: asking again does not fix invalid metadata or a rejected request
            breakerLog = error == null ? breaker.succeeded(permit) : breaker.failed(permit, now, unwrap(error));
            previous = cached == null ? null : cached.metadata;
            if (error == null) {
                // A 404 right after published metadata may come from a proxy during a rollout, so
                // it is checked again soon instead of pinning the default paths for a full interval
                Optional<PdpMetadata> metadata = fetched.metadata();
                boolean unpublished = metadata.isEmpty() && lastPublished;
                long expiresAt = now + ttlNanos(fetched, unpublished);
                cached = new Cached(metadata, expiresAt, expiresAt + staleNanos(fetched));
                lastPublished = metadata.isPresent();
                value = metadata;
            } else {
                Throwable cause = unwrap(error);
                boolean communication = cause instanceof PdpCommunicationException;
                if (communication && cached != null && now - cached.staleUntilNanos < 0) {
                    // Try again after minTtl, but never use the previous result past maxStale
                    long retryAt = now + minTtlNanos;
                    long refreshAt = retryAt - cached.staleUntilNanos < 0 ? retryAt : cached.staleUntilNanos;
                    cached = new Cached(cached.metadata, refreshAt, cached.staleUntilNanos);
                    value = cached.metadata;
                    keptPrevious = true;
                } else {
                    // Past maxStale, or the PDP answered with something unusable: the previous
                    // result must not come back on a later communication failure
                    droppedStale = cached != null && communication;
                    droppedUnusable = cached != null && !communication;
                    cached = null;
                    failure = cause instanceof AuthZenException authZenException ? authZenException
                            : new AuthZenException("PDP metadata discovery failed unexpectedly", cause);
                }
            }
        } catch (RuntimeException e) {
            // pending is already cleared, so the waiting callers must be completed in any case
            failure = new AuthZenException("PDP metadata discovery failed unexpectedly", e);
            keptPrevious = false;
            droppedStale = false;
            droppedUnusable = false;
        } finally {
            lock.unlock();
        }
        // Complete before logging, so that a failing logger cannot leave the waiting callers blocked
        if (failure != null) {
            result.completeExceptionally(failure);
            breakerLog.run();
            if (droppedStale) {
                LOGGER.log(Level.WARNING, "PDP metadata from " + location + " expired more than maxStale ago and "
                        + "could not be refreshed; requests fail until a refresh succeeds: " + failure.getMessage());
            } else if (droppedUnusable) {
                LOGGER.log(Level.WARNING, "PDP metadata from " + location + " can no longer be used; dropping the "
                        + "previous result, and requests fail until a refresh succeeds: " + failure.getMessage());
            } else {
                LOGGER.log(Level.DEBUG, () -> "PDP metadata discovery at " + location + " failed: " + error);
            }
            return;
        }
        result.complete(value);
        breakerLog.run();
        if (keptPrevious) {
            LOGGER.log(Level.WARNING, "Refreshing the PDP metadata from " + location
                    + " failed; keeping the previous result: " + unwrap(error).getMessage());
        } else if (!value.equals(previous)) {
            logChange(value);
        }
    }

    /** Reads the clock; a failing clock fails the request instead of leaving it pending. */
    private long now() {
        try {
            return clock.getAsLong();
        } catch (RuntimeException e) {
            throw new AuthZenException("Clock of the PDP metadata cache failed", e);
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
