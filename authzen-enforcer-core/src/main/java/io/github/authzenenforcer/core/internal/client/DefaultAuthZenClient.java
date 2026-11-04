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
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.ActionSearchRequest;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.ResourceSearchRequest;
import io.github.authzenenforcer.core.model.SearchResponse;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.model.SubjectSearchRequest;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
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
import java.util.function.Function;
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

    /** Default path of the Subject Search endpoint below the PDP URL. */
    static final String SEARCH_SUBJECT_PATH = "/access/v1/search/subject";

    /** Default path of the Resource Search endpoint below the PDP URL. */
    static final String SEARCH_RESOURCE_PATH = "/access/v1/search/resource";

    /** Default path of the Action Search endpoint below the PDP URL. */
    static final String SEARCH_ACTION_PATH = "/access/v1/search/action";

    private final Api evaluationApi;
    private final Api evaluationsApi;
    private final Api searchSubjectApi;
    private final Api searchResourceApi;
    private final Api searchActionApi;
    private final MetadataResolver metadata;
    private final PdpTransport transport;
    private final CircuitBreaker breaker;
    private final LongSupplier clock;

    /**
     * An API of the PDP and where to find it.
     *
     * @param name      the name of the API in messages
     * @param override  the endpoint set on the builder, or {@code null}
     * @param fallback  the default path below the PDP URL, used when the PDP publishes no metadata
     * @param listed    the endpoint the metadata lists; empty if the PDP does not offer the API
     * @param parameter the metadata parameter of the endpoint
     */
    private record Api(String name, URI override, URI fallback, Function<PdpMetadata, Optional<URI>> listed,
            String parameter) {
    }

    /**
     * Creates a client. Use {@link AuthZenClient#builder()} instead.
     *
     * @param pdpUrl         the base URL of the PDP, also its identifier in the metadata
     * @param overrides      the endpoints set on the builder; the others are discovered or use
     *                       the default paths
     * @param credentials    the credential provider, or {@code null}
     * @param codec          the JSON codec
     * @param httpClient     an HTTP client that does not follow redirects
     * @param requestTimeout the timeout of a single request
     * @param maxRetries     how often a request that failed transiently may be repeated
     * @param discovery      how discovered metadata is cached, or {@code null} to use no discovery
     * @param circuitBreaker how the breakers for discovery and the APIs behave
     * @param clock          a monotonic clock in nanoseconds for the metadata cache and the
     *                       breakers, such as {@link System#nanoTime()}
     */
    public DefaultAuthZenClient(URI pdpUrl, EndpointOverrides overrides, CredentialProvider credentials,
            JsonCodec codec, HttpClient httpClient, Duration requestTimeout, int maxRetries,
            DiscoveryOptions discovery, CircuitBreakerOptions circuitBreaker, LongSupplier clock) {
        URI identifier = Urls.withoutTrailingSlashes(Urls.check(pdpUrl, "pdpUrl"));
        Objects.requireNonNull(overrides, "overrides");
        this.evaluationApi = new Api("Access Evaluation", checked(overrides.evaluation(), "evaluationEndpoint"),
                Urls.append(identifier, EVALUATION_PATH),
                current -> Optional.of(current.getAccessEvaluationEndpoint()), "access_evaluation_endpoint");
        this.evaluationsApi = new Api("Access Evaluations", checked(overrides.evaluations(), "evaluationsEndpoint"),
                Urls.append(identifier, EVALUATIONS_PATH),
                PdpMetadata::getAccessEvaluationsEndpoint, "access_evaluations_endpoint");
        this.searchSubjectApi = new Api("Subject Search", checked(overrides.searchSubject(), "searchSubjectEndpoint"),
                Urls.append(identifier, SEARCH_SUBJECT_PATH),
                PdpMetadata::getSearchSubjectEndpoint, "search_subject_endpoint");
        this.searchResourceApi = new Api("Resource Search",
                checked(overrides.searchResource(), "searchResourceEndpoint"),
                Urls.append(identifier, SEARCH_RESOURCE_PATH),
                PdpMetadata::getSearchResourceEndpoint, "search_resource_endpoint");
        this.searchActionApi = new Api("Action Search", checked(overrides.searchAction(), "searchActionEndpoint"),
                Urls.append(identifier, SEARCH_ACTION_PATH),
                PdpMetadata::getSearchActionEndpoint, "search_action_endpoint");
        this.transport = new PdpTransport(httpClient, credentials, codec, requestTimeout, maxRetries);
        this.clock = Objects.requireNonNull(clock, "clock");
        // Only jitters the circuit breaker's open interval; nothing security-sensitive depends on it
        DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble(); // NOSONAR java:S2245
        this.breaker = new CircuitBreaker("PDP authorization API", circuitBreaker, random);
        this.metadata = discovery != null
                ? new MetadataResolver(transport, identifier, discovery, credentials != null, clock,
                        new CircuitBreaker("PDP metadata discovery", circuitBreaker, random))
                : null;
        if (credentials != null) {
            // An endpoint that is not overridden may be a default path below the PDP URL
            Set<URI> targets = new LinkedHashSet<>();
            for (Api api : List.of(evaluationApi, evaluationsApi, searchSubjectApi, searchResourceApi, searchActionApi)) {
                targets.add(api.override() != null ? api.override() : identifier);
            }
            targets.stream().filter(Urls::isPlainHttpToRemoteHost).forEach(target -> LOGGER.log(Level.WARNING,
                    "PDP endpoint " + target + " uses plain HTTP; credentials are sent unencrypted"));
        }
    }

    private static URI checked(URI endpoint, String name) {
        return endpoint != null ? Urls.check(endpoint, name) : null;
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
        return call(evaluationApi, WireFormat.evaluationRequest(request), WireFormat::evaluationResponse);
    }

    private <T> CompletableFuture<T> call(Api api, Map<String, Object> body, Function<Object, T> reader) {
        BreakerLog log = new BreakerLog();
        CompletableFuture<T> result = endpoint(api)
                .thenCompose(endpoint -> post(endpoint, body, log))
                .thenApply(reader);
        // A separate stage after the caller's future: a slow or failing logger can neither delay
        // nor change the result
        result.whenComplete((ignored, error) -> log.run());
        return result;
    }

    private CompletableFuture<Object> post(URI endpoint, Map<String, Object> body, BreakerLog log) {
        CircuitBreaker.Permit permit;
        try {
            permit = breaker.acquire(now());
        } catch (AuthZenException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Object> response;
        try {
            response = transport.post(endpoint, body);
        } catch (RuntimeException e) {
            breaker.released(permit);
            return CompletableFuture.failedFuture(e);
        } catch (Error e) {
            // A lost probe permit would keep the breaker rejecting every call
            breaker.released(permit);
            throw e;
        }
        return response.whenComplete((ignored, error) -> log.action = record(permit, error));
    }

    // Only an unreachable or overloaded PDP counts; any answer shows that it is up
    private Runnable record(CircuitBreaker.Permit permit, Throwable error) {
        Throwable cause = unwrap(error);
        if (cause == null || cause instanceof PdpProtocolException || cause instanceof PdpAuthenticationException) {
            return breaker.succeeded(permit);
        }
        if (cause instanceof PdpCommunicationException) {
            long now;
            try {
                now = now();
            } catch (AuthZenException e) {
                breaker.released(permit);
                return BreakerLog.NONE;
            }
            return breaker.failed(permit, now, cause);
        }
        // A failing credential provider, a cancelled call or a serialization error says nothing about the PDP
        breaker.released(permit);
        return BreakerLog.NONE;
    }

    /** The log action of the breaker, run once the caller's future has completed. */
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
        return call(evaluationsApi, WireFormat.evaluationsRequest(request),
                json -> WireFormat.evaluationsResponse(json, request));
    }

    @Override
    public SearchResponse<Subject> searchSubjects(SubjectSearchRequest request) {
        return await(subjectSearch(request), "Subject search");
    }

    @Override
    public CompletionStage<SearchResponse<Subject>> searchSubjectsAsync(SubjectSearchRequest request) {
        return subjectSearch(request).minimalCompletionStage();
    }

    private CompletableFuture<SearchResponse<Subject>> subjectSearch(SubjectSearchRequest request) {
        Objects.requireNonNull(request, "request");
        return call(searchSubjectApi, WireFormat.subjectSearchRequest(request), WireFormat::subjectSearchResponse);
    }

    @Override
    public SearchResponse<Resource> searchResources(ResourceSearchRequest request) {
        return await(resourceSearch(request), "Resource search");
    }

    @Override
    public CompletionStage<SearchResponse<Resource>> searchResourcesAsync(ResourceSearchRequest request) {
        return resourceSearch(request).minimalCompletionStage();
    }

    private CompletableFuture<SearchResponse<Resource>> resourceSearch(ResourceSearchRequest request) {
        Objects.requireNonNull(request, "request");
        return call(searchResourceApi, WireFormat.resourceSearchRequest(request), WireFormat::resourceSearchResponse);
    }

    @Override
    public SearchResponse<Action> searchActions(ActionSearchRequest request) {
        return await(actionSearch(request), "Action search");
    }

    @Override
    public CompletionStage<SearchResponse<Action>> searchActionsAsync(ActionSearchRequest request) {
        return actionSearch(request).minimalCompletionStage();
    }

    private CompletableFuture<SearchResponse<Action>> actionSearch(ActionSearchRequest request) {
        Objects.requireNonNull(request, "request");
        return call(searchActionApi, WireFormat.actionSearchRequest(request), WireFormat::actionSearchResponse);
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
    private CompletableFuture<URI> endpoint(Api api) {
        if (api.override() != null) {
            return CompletableFuture.completedFuture(api.override());
        }
        return discovered().thenApply(current -> {
            if (current.isEmpty()) {
                return api.fallback();
            }
            return api.listed().apply(current.get()).orElseThrow(() -> new PdpProtocolException(
                    "PDP does not offer the " + api.name() + " API: its metadata lists no " + api.parameter()));
        });
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
