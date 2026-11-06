package io.github.authzenenforcer.core.internal.client;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpAuthenticationException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.client.CircuitBreakerOptions;
import io.github.authzenenforcer.core.client.DiscoveryOptions;
import io.github.authzenenforcer.core.client.PaginationOptions;
import io.github.authzenenforcer.core.event.BreakerTransition;
import io.github.authzenenforcer.core.event.ListenerDrops;
import io.github.authzenenforcer.core.event.Operation;
import io.github.authzenenforcer.core.internal.event.CallTrace;
import io.github.authzenenforcer.core.internal.event.EventDispatcher;
import io.github.authzenenforcer.core.internal.http.PdpTransport;
import io.github.authzenenforcer.core.internal.wire.WireFormat;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.ActionSearchRequest;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.PageRequest;
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
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

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

    private static final String SUBJECT_SEARCH = "Subject search";
    private static final String RESOURCE_SEARCH = "Resource search";
    private static final String ACTION_SEARCH = "Action search";

    /** What callers read in the message of an unexpected failure, such as "Evaluation failed unexpectedly". */
    private static final Map<Operation<?, ?>, String> DESCRIPTIONS = Map.of(
            Operation.EVALUATION, "Evaluation",
            Operation.EVALUATIONS, "Evaluations",
            Operation.SUBJECT_SEARCH, SUBJECT_SEARCH,
            Operation.RESOURCE_SEARCH, RESOURCE_SEARCH,
            Operation.ACTION_SEARCH, ACTION_SEARCH);

    private final Api evaluationApi;
    private final Api evaluationsApi;
    private final Api searchSubjectApi;
    private final Api searchResourceApi;
    private final Api searchActionApi;
    private final MetadataResolver metadata;
    private final PdpTransport transport;
    private final CircuitBreaker breaker;
    private final PaginationOptions pagination;
    private final Search<SubjectSearchRequest, Subject> subjectSearches;
    private final Search<ResourceSearchRequest, Resource> resourceSearches;
    private final Search<ActionSearchRequest, Action> actionSearches;
    private final LongSupplier clock;
    private final EventDispatcher events;

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
     * A Search API and how to walk its pages.
     *
     * @param operation the name of the search in messages
     * @param call      sends the request for one page
     * @param page      the page a request asks for
     * @param withToken copies a request with another page token
     * @param <R>       the type of the request
     * @param <T>       the type of the results
     */
    private record Search<R, T>(String operation, Function<R, CompletableFuture<SearchResponse<T>>> call,
            Function<R, Optional<PageRequest>> page, BiFunction<R, String, R> withToken) {

        PageCursor<R, T> cursor(R request, PaginationOptions options) {
            return new PageCursor<>(operation, request, page.apply(request).flatMap(PageRequest::getToken),
                    withToken, options);
        }
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
     * @param pagination     the limits for searches over all pages
     * @param clock          a monotonic clock in nanoseconds for the metadata cache and the
     *                       breakers, such as {@link System#nanoTime()}
     * @param events         where events for the listeners go
     */
    public DefaultAuthZenClient(URI pdpUrl, EndpointOverrides overrides, CredentialProvider credentials,
            JsonCodec codec, HttpClient httpClient, Duration requestTimeout, int maxRetries,
            DiscoveryOptions discovery, CircuitBreakerOptions circuitBreaker, PaginationOptions pagination,
            LongSupplier clock, EventDispatcher events) {
        URI identifier = Urls.withoutTrailingSlashes(Urls.check(pdpUrl, "pdpUrl"));
        Objects.requireNonNull(overrides, "overrides must not be null");
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
        this.pagination = Objects.requireNonNull(pagination, "pagination must not be null");
        this.subjectSearches = new Search<>(SUBJECT_SEARCH, this::subjectSearch, SubjectSearchRequest::getPage,
                SubjectSearchRequest::withPageToken);
        this.resourceSearches = new Search<>(RESOURCE_SEARCH, this::resourceSearch, ResourceSearchRequest::getPage,
                ResourceSearchRequest::withPageToken);
        this.actionSearches = new Search<>(ACTION_SEARCH, this::actionSearch, ActionSearchRequest::getPage,
                ActionSearchRequest::withPageToken);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        // Only jitters the circuit breaker's open interval; nothing security-sensitive depends on it
        DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble(); // NOSONAR java:S2245
        this.breaker = new CircuitBreaker("PDP authorization API", BreakerTransition.Breaker.AUTHORIZATION_API,
                circuitBreaker, random, events);
        this.metadata = discovery != null
                ? new MetadataResolver(transport, identifier, discovery, credentials != null, clock,
                        new CircuitBreaker("PDP metadata discovery", BreakerTransition.Breaker.METADATA_DISCOVERY,
                                circuitBreaker, random, events), events)
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
        Objects.requireNonNull(request, "request must not be null");
        return call(Operation.EVALUATION, request, evaluationApi, WireFormat.evaluationRequest(request),
                WireFormat::evaluationResponse);
    }

    private <Q, R> CompletableFuture<R> call(Operation<Q, R> operation, Q request, Api api, Map<String, Object> body,
            Function<Object, R> reader) {
        BreakerLog log = new BreakerLog();
        CallTrace trace = CallTrace.start(events, operation);
        CompletableFuture<R> result = new CompletableFuture<>();
        endpoint(api)
                .thenCompose(endpoint -> {
                    trace.endpoint(endpoint);
                    return post(endpoint, body, log, trace);
                })
                .thenApply(reader)
                // Every failure becomes an AuthZenException once, so that sync and async callers
                // and the listeners all get the same exception
                .whenComplete((response, error) -> {
                    if (error != null) {
                        result.completeExceptionally(asAuthZenException(error, DESCRIPTIONS.get(operation)));
                    } else {
                        result.complete(response);
                    }
                });
        // A separate stage after the caller's future: a slow or failing logger or listener can
        // neither delay nor change the result
        result.whenComplete((response, error) -> {
            try {
                log.run();
            } finally {
                trace.completed(operation, request, response,
                        error != null ? asAuthZenException(error, DESCRIPTIONS.get(operation)) : null);
            }
        });
        return result;
    }

    private CompletableFuture<Object> post(URI endpoint, Map<String, Object> body, BreakerLog log, CallTrace trace) {
        CircuitBreaker.Permit permit;
        try {
            permit = breaker.acquire(now());
        } catch (AuthZenException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Object> response;
        try {
            response = transport.post(endpoint, body, trace);
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
        Objects.requireNonNull(request, "request must not be null");
        return call(Operation.EVALUATIONS, request, evaluationsApi, WireFormat.evaluationsRequest(request),
                json -> WireFormat.evaluationsResponse(json, request));
    }

    @Override
    public SearchResponse<Subject> searchSubjects(SubjectSearchRequest request) {
        return await(subjectSearch(request), SUBJECT_SEARCH);
    }

    @Override
    public CompletionStage<SearchResponse<Subject>> searchSubjectsAsync(SubjectSearchRequest request) {
        return subjectSearch(request).minimalCompletionStage();
    }

    private CompletableFuture<SearchResponse<Subject>> subjectSearch(SubjectSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return call(Operation.SUBJECT_SEARCH, request, searchSubjectApi, WireFormat.subjectSearchRequest(request),
                WireFormat::subjectSearchResponse);
    }

    @Override
    public SearchResponse<Resource> searchResources(ResourceSearchRequest request) {
        return await(resourceSearch(request), RESOURCE_SEARCH);
    }

    @Override
    public CompletionStage<SearchResponse<Resource>> searchResourcesAsync(ResourceSearchRequest request) {
        return resourceSearch(request).minimalCompletionStage();
    }

    private CompletableFuture<SearchResponse<Resource>> resourceSearch(ResourceSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return call(Operation.RESOURCE_SEARCH, request, searchResourceApi, WireFormat.resourceSearchRequest(request),
                WireFormat::resourceSearchResponse);
    }

    @Override
    public SearchResponse<Action> searchActions(ActionSearchRequest request) {
        return await(actionSearch(request), ACTION_SEARCH);
    }

    @Override
    public CompletionStage<SearchResponse<Action>> searchActionsAsync(ActionSearchRequest request) {
        return actionSearch(request).minimalCompletionStage();
    }

    private CompletableFuture<SearchResponse<Action>> actionSearch(ActionSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return call(Operation.ACTION_SEARCH, request, searchActionApi, WireFormat.actionSearchRequest(request),
                WireFormat::actionSearchResponse);
    }

    @Override
    public Stream<Subject> searchAllSubjects(SubjectSearchRequest request) {
        return searchAllSubjects(request, pagination);
    }

    @Override
    public Stream<Subject> searchAllSubjects(SubjectSearchRequest request, PaginationOptions options) {
        return all(subjectSearches, request, options);
    }

    @Override
    public Flow.Publisher<Subject> searchAllSubjectsAsync(SubjectSearchRequest request) {
        return searchAllSubjectsAsync(request, pagination);
    }

    @Override
    public Flow.Publisher<Subject> searchAllSubjectsAsync(SubjectSearchRequest request, PaginationOptions options) {
        return publisher(subjectSearches, request, options);
    }

    @Override
    public Stream<Resource> searchAllResources(ResourceSearchRequest request) {
        return searchAllResources(request, pagination);
    }

    @Override
    public Stream<Resource> searchAllResources(ResourceSearchRequest request, PaginationOptions options) {
        return all(resourceSearches, request, options);
    }

    @Override
    public Flow.Publisher<Resource> searchAllResourcesAsync(ResourceSearchRequest request) {
        return searchAllResourcesAsync(request, pagination);
    }

    @Override
    public Flow.Publisher<Resource> searchAllResourcesAsync(ResourceSearchRequest request,
            PaginationOptions options) {
        return publisher(resourceSearches, request, options);
    }

    @Override
    public Stream<Action> searchAllActions(ActionSearchRequest request) {
        return searchAllActions(request, pagination);
    }

    @Override
    public Stream<Action> searchAllActions(ActionSearchRequest request, PaginationOptions options) {
        return all(actionSearches, request, options);
    }

    @Override
    public Flow.Publisher<Action> searchAllActionsAsync(ActionSearchRequest request) {
        return searchAllActionsAsync(request, pagination);
    }

    @Override
    public Flow.Publisher<Action> searchAllActionsAsync(ActionSearchRequest request, PaginationOptions options) {
        return publisher(actionSearches, request, options);
    }

    private static <R, T> Stream<T> all(Search<R, T> search, R request, PaginationOptions options) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(options, "options must not be null");
        return PageCursor.stream(search.cursor(request, options),
                next -> await(search.call().apply(next), search.operation()));
    }

    private static <R, T> Flow.Publisher<T> publisher(Search<R, T> search, R request, PaginationOptions options) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(options, "options must not be null");
        return new PagePublisher<>(search.operation(), () -> search.cursor(request, options),
                next -> search.call().apply(next), error -> asAuthZenException(error, search.operation()));
    }

    @Override
    public Optional<PdpMetadata> metadata() {
        return await(discovered(), "Metadata discovery");
    }

    @Override
    public CompletionStage<Optional<PdpMetadata>> metadataAsync() {
        return discovered().minimalCompletionStage();
    }

    @Override
    public ListenerDrops listenerDrops() {
        return events.drops();
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
