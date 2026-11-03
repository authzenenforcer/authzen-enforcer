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
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
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

    /** Default path of the Access Evaluations endpoint below the PDP URL. */
    static final String EVALUATIONS_PATH = "/access/v1/evaluations";

    private final URI defaultEvaluationEndpoint;
    private final URI evaluationEndpoint;
    private final URI defaultEvaluationsEndpoint;
    private final URI evaluationsEndpoint;
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
     * @param evaluationsEndpoint     the Access Evaluations endpoint, or {@code null} to discover it
     *                                or use the default path
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
    public DefaultAuthZenClient(URI pdpUrl, URI evaluationEndpoint, URI evaluationsEndpoint,
            CredentialProvider credentials, JsonCodec codec,
            HttpClient httpClient, Duration requestTimeout, int maxRetries, DiscoveryOptions discovery,
            CircuitBreakerOptions circuitBreaker, LongSupplier clock) {
        URI identifier = Urls.withoutTrailingSlashes(Urls.check(pdpUrl, "pdpUrl"));
        this.defaultEvaluationEndpoint = Urls.append(identifier, EVALUATION_PATH);
        this.evaluationEndpoint = evaluationEndpoint != null ? Urls.check(evaluationEndpoint, "evaluationEndpoint") : null;
        this.defaultEvaluationsEndpoint = Urls.append(identifier, EVALUATIONS_PATH);
        this.evaluationsEndpoint = evaluationsEndpoint != null
                ? Urls.check(evaluationsEndpoint, "evaluationsEndpoint") : null;
        this.transport = new PdpTransport(httpClient, credentials, codec, requestTimeout, maxRetries);
        this.clock = Objects.requireNonNull(clock, "clock");
        DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();
        this.evaluations = new CircuitBreaker("PDP evaluation", circuitBreaker, random);
        this.metadata = discovery != null
                ? new MetadataResolver(transport, identifier, discovery, credentials != null, clock,
                        new CircuitBreaker("PDP metadata discovery", circuitBreaker, random))
                : null;
        if (credentials != null) {
            // An endpoint that is not overridden may be a default path below the PDP URL
            Set<URI> targets = new LinkedHashSet<>();
            targets.add(this.evaluationEndpoint != null ? this.evaluationEndpoint : identifier);
            targets.add(this.evaluationsEndpoint != null ? this.evaluationsEndpoint : identifier);
            targets.stream().filter(Urls::isPlainHttpToRemoteHost).forEach(target -> LOGGER.log(Level.WARNING,
                    "PDP endpoint " + target + " uses plain HTTP; credentials are sent unencrypted"));
        }
    }

    @Override
    public EvaluationResponse evaluate(EvaluationRequest request) {
        return await(evaluation(request), "Evaluation");
    }

    // Callers get a stage they cannot complete; the SDK keeps the future
    @Override
    public CompletionStage<EvaluationResponse> evaluateAsync(EvaluationRequest request) {
        return evaluation(request).minimalCompletionStage();
    }

    private CompletableFuture<EvaluationResponse> evaluation(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> body = WireFormat.evaluationRequest(request);
        BreakerLog log = new BreakerLog();
        CompletableFuture<EvaluationResponse> result = evaluationEndpoint()
                .thenCompose(endpoint -> post(endpoint, body, log))
                .thenApply(WireFormat::evaluationResponse);
        // A separate stage after the caller's future: a slow or failing logger can neither delay
        // nor change the decision
        result.whenComplete((ignored, error) -> log.run());
        return result;
    }

    private CompletableFuture<Object> post(URI endpoint, Map<String, Object> body, BreakerLog log) {
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
        } catch (Error e) {
            // A lost probe permit would keep the breaker rejecting every call
            evaluations.released(permit);
            throw e;
        }
        return response.whenComplete((ignored, error) -> log.action = record(permit, error));
    }

    // Only an unreachable or overloaded PDP counts; any answer shows that it is up
    private Runnable record(CircuitBreaker.Permit permit, Throwable error) {
        Throwable cause = unwrap(error);
        if (cause == null || cause instanceof PdpProtocolException || cause instanceof PdpAuthenticationException) {
            return evaluations.succeeded(permit);
        }
        if (cause instanceof PdpCommunicationException) {
            long now;
            try {
                now = now();
            } catch (AuthZenException e) {
                evaluations.released(permit);
                return BreakerLog.NONE;
            }
            return evaluations.failed(permit, now, cause);
        }
        // A failing credential provider, a cancelled call or a serialization error says nothing about the PDP
        evaluations.released(permit);
        return BreakerLog.NONE;
    }

    /** The log action of the evaluation breaker, run once the caller's future has completed. */
    private static final class BreakerLog implements Runnable {

        static final Runnable NONE = () -> { };

        // Written by the transport's completion before the result completes, read after it
        volatile Runnable action = NONE;

        @Override
        public void run() {
            action.run();
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
    public EvaluationsResponse evaluations(EvaluationsRequest request) {
        return await(batch(request), "Evaluations");
    }

    @Override
    public CompletionStage<EvaluationsResponse> evaluationsAsync(EvaluationsRequest request) {
        return batch(request).minimalCompletionStage();
    }

    private CompletableFuture<EvaluationsResponse> batch(EvaluationsRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, Object> body = WireFormat.evaluationsRequest(request);
        BreakerLog log = new BreakerLog();
        CompletableFuture<EvaluationsResponse> result = evaluationsEndpoint()
                .thenCompose(endpoint -> post(endpoint, body, log))
                .thenApply(json -> WireFormat.evaluationsResponse(json, request));
        result.whenComplete((ignored, error) -> log.run());
        return result;
    }

    @Override
    public Optional<PdpMetadata> metadata() {
        return await(discovered(), "Metadata discovery");
    }

    @Override
    public CompletionStage<Optional<PdpMetadata>> metadataAsync() {
        return discovered().minimalCompletionStage();
    }

    private CompletableFuture<Optional<PdpMetadata>> discovered() {
        return metadata != null ? metadata.metadata() : CompletableFuture.completedFuture(Optional.empty());
    }

    // Published metadata without the endpoint means the PDP does not offer the API (section 9.1.1)
    private CompletableFuture<URI> evaluationsEndpoint() {
        if (evaluationsEndpoint != null) {
            return CompletableFuture.completedFuture(evaluationsEndpoint);
        }
        return discovered().thenApply(current -> {
            if (current.isEmpty()) {
                return defaultEvaluationsEndpoint;
            }
            return current.get().getAccessEvaluationsEndpoint().orElseThrow(() -> new PdpProtocolException(
                    "PDP does not offer the Access Evaluations API: its metadata lists no access_evaluations_endpoint"));
        });
    }

    private CompletableFuture<URI> evaluationEndpoint() {
        if (evaluationEndpoint != null) {
            return CompletableFuture.completedFuture(evaluationEndpoint);
        }
        return discovered().thenApply(current -> current
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
