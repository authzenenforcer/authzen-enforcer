package io.github.authzenenforcer.core.internal.client;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpAuthenticationException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.client.CircuitBreakerOptions;
import io.github.authzenenforcer.core.client.DiscoveryOptions;
import io.github.authzenenforcer.core.internal.http.PdpTransport;
import io.github.authzenenforcer.core.internal.wire.WireFormat;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Default {@link AuthZenClient} on top of {@link java.net.http.HttpClient}.
 */
public final class DefaultAuthZenClient implements AuthZenClient {

    private static final System.Logger LOGGER = System.getLogger(DefaultAuthZenClient.class.getName());

    /** Default path of the Access Evaluation endpoint below the PDP URL. */
    static final String EVALUATION_PATH = "/access/v1/evaluation";

    private final URI defaultEvaluationEndpoint;
    private final URI evaluationEndpoint;
    private final MetadataResolver metadata;
    private final PdpTransport transport;
    private final CircuitBreaker evaluations;
    private final LongSupplier clock;

    /**
     * Creates a client. Use {@link AuthZenClient#builder()} instead.
     *
     * @param pdpUrl                  the base URL of the PDP, also its identifier in the metadata
     * @param evaluationEndpoint      the evaluation endpoint, or {@code null} to discover it or use
     *                                the default path
     * @param credentials             the credential provider, or {@code null}
     * @param codec                   the JSON codec
     * @param httpClient              an HTTP client that does not follow redirects
     * @param requestTimeout          the timeout of a single request
     * @param maxRetries              how often a request that failed transiently may be repeated
     * @param discovery               how discovered metadata is cached, or {@code null} to use no
     *                                discovery
     * @param circuitBreaker          how the breakers for discovery and evaluations behave
     * @param clock                   a monotonic clock in nanoseconds for the metadata cache and the
     *                                breakers, such as {@link System#nanoTime()}
     */
    public DefaultAuthZenClient(URI pdpUrl, URI evaluationEndpoint, CredentialProvider credentials, JsonCodec codec,
            HttpClient httpClient, Duration requestTimeout, int maxRetries, DiscoveryOptions discovery,
            CircuitBreakerOptions circuitBreaker, LongSupplier clock) {
        URI identifier = Urls.withoutTrailingSlashes(Urls.check(pdpUrl, "pdpUrl"));
        this.defaultEvaluationEndpoint = Urls.append(identifier, EVALUATION_PATH);
        this.evaluationEndpoint = evaluationEndpoint != null ? Urls.check(evaluationEndpoint, "evaluationEndpoint") : null;
        this.transport = new PdpTransport(httpClient, credentials, codec, requestTimeout, maxRetries);
        this.clock = Objects.requireNonNull(clock, "clock");
        DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();
        this.evaluations = new CircuitBreaker("PDP evaluation", circuitBreaker, random);
        this.metadata = discovery != null
                ? new MetadataResolver(transport, identifier, discovery, credentials != null, clock,
                        new CircuitBreaker("PDP metadata discovery", circuitBreaker, random))
                : null;
        URI configured = this.evaluationEndpoint != null ? this.evaluationEndpoint : identifier;
        if (credentials != null && Urls.isPlainHttpToRemoteHost(configured)) {
            LOGGER.log(Level.WARNING, "PDP endpoint " + configured + " uses plain HTTP; credentials are sent unencrypted");
        }
    }

    @Override
    public EvaluationResponse evaluate(EvaluationRequest request) {
        return await(evaluateAsync(request), "Evaluation");
    }

    @Override
    public CompletableFuture<EvaluationResponse> evaluateAsync(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> body = WireFormat.evaluationRequest(request);
        return evaluationEndpoint()
                .thenCompose(endpoint -> post(endpoint, body))
                .thenApply(WireFormat::evaluationResponse);
    }

    private CompletableFuture<Object> post(URI endpoint, Map<String, Object> body) {
        CircuitBreaker.Permit permit;
        try {
            permit = evaluations.acquire(now());
        } catch (AuthZenException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Object> response;
        try {
            response = transport.post(endpoint, body);
        } catch (RuntimeException e) {
            evaluations.released(permit);
            return CompletableFuture.failedFuture(e);
        }
        return response.whenComplete((ignored, error) -> record(permit, error));
    }

    // Only an unreachable or overloaded PDP counts; any answer shows that it is up
    private void record(CircuitBreaker.Permit permit, Throwable error) {
        Throwable cause = unwrap(error);
        Runnable log;
        if (cause == null || cause instanceof PdpProtocolException || cause instanceof PdpAuthenticationException) {
            log = evaluations.succeeded(permit);
        } else if (cause instanceof PdpCommunicationException) {
            long now;
            try {
                now = now();
            } catch (AuthZenException e) {
                evaluations.released(permit);
                return;
            }
            log = evaluations.failed(permit, now, cause);
        } else {
            // A failing credential provider, a cancelled call or a serialization error says nothing about the PDP
            evaluations.released(permit);
            return;
        }
        // This runs inside whenComplete, where a throwing logger would turn the PDP's answer into a failure
        try {
            log.run();
        } catch (RuntimeException e) {
            // Logging is best effort
        }
    }

    private long now() {
        try {
            return clock.getAsLong();
        } catch (RuntimeException e) {
            throw new AuthZenException("Clock of the circuit breaker failed", e);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public Optional<PdpMetadata> metadata() {
        return await(metadataAsync(), "Metadata discovery");
    }

    @Override
    public CompletableFuture<Optional<PdpMetadata>> metadataAsync() {
        return metadata != null ? metadata.metadata() : CompletableFuture.completedFuture(Optional.empty());
    }

    private CompletableFuture<URI> evaluationEndpoint() {
        if (evaluationEndpoint != null) {
            return CompletableFuture.completedFuture(evaluationEndpoint);
        }
        return metadataAsync().thenApply(current -> current
                .map(PdpMetadata::getAccessEvaluationEndpoint)
                .orElse(defaultEvaluationEndpoint));
    }

    private static <T> T await(CompletableFuture<T> future, String operation) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PdpCommunicationException("Interrupted while waiting for the PDP", e);
        } catch (ExecutionException e) {
            throw asAuthZenException(e.getCause(), operation);
        }
    }

    private static AuthZenException asAuthZenException(Throwable error, String operation) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof AuthZenException authZenException) {
            return authZenException;
        }
        return new AuthZenException(operation + " failed unexpectedly", cause);
    }
}
