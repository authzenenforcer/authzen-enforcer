package io.github.authzenenforcer.core.client;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.event.EventContent;
import io.github.authzenenforcer.core.event.ListenerDrops;
import io.github.authzenenforcer.core.internal.event.EventDispatcher;
import io.github.authzenenforcer.core.internal.client.DefaultAuthZenClient;
import io.github.authzenenforcer.core.internal.client.EndpointOverrides;
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
import io.github.authzenenforcer.core.spi.PepListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * Client for the AuthZEN Authorization API of a PDP.
 *
 * <pre>{@code
 * AuthZenClient client = AuthZenClient.builder()
 *         .pdpUrl("https://pdp.example.com")
 *         .credentials(CredentialProvider.bearer(tokenSource::currentToken))
 *         .build();
 *
 * EvaluationResponse response = client.evaluate(EvaluationRequest.of(
 *         Subject.of("user", "alice"), Action.of("read"), Resource.of("document", "123")));
 * }</pre>
 *
 * <p>Endpoints are discovered from the metadata the PDP publishes at
 * {@code /.well-known/authzen-configuration} when they are first needed; see
 * {@link Builder#discovery(boolean)}.
 *
 * <p>A deny is a regular response with {@code decision} {@code false}. When no decision can be
 * obtained, for example because the PDP is unreachable or answers with an error, the client throws
 * an {@link AuthZenException}; callers must treat it as a deny.
 *
 * <p>Instances are thread-safe and meant to be long-lived: create one per PDP and share it.
 */
public interface AuthZenClient {

    /**
     * Asks the PDP for an access decision and waits for the answer.
     *
     * @param request the request
     * @return the decision
     * @throws AuthZenException if no decision could be obtained
     */
    EvaluationResponse evaluate(EvaluationRequest request);

    /**
     * Asks the PDP for an access decision without blocking.
     *
     * <p>The stage cannot be completed by its holder, so code it is handed to cannot make up a
     * decision. Call {@code toCompletableFuture()} for {@code join()} or {@code get()}; that gives
     * a separate future.
     *
     * @param request the request
     * @return a stage completed with the decision, or exceptionally with an {@link AuthZenException}
     *         (wrapped in a {@link java.util.concurrent.CompletionException} by dependent stages)
     */
    CompletionStage<EvaluationResponse> evaluateAsync(EvaluationRequest request);

    /**
     * Asks the PDP for several decisions in one call (Access Evaluations API) and waits for the answer.
     *
     * <p>The request is sent as built; the PDP applies its defaults to the items. If the PDP publishes
     * metadata without an {@code access_evaluations_endpoint}, it does not offer this API, and the
     * call fails without contacting it. Use {@link #evaluate(EvaluationRequest)} for each item then.
     *
     * <p>Batch calls share the circuit breaker of single evaluations. A batch that times out counts
     * as a failure like any other, so keep batches small enough to finish within the request
     * timeout, in particular if their size depends on user input; otherwise slow batches can open
     * the breaker for single evaluations too.
     *
     * @param request the request
     * @return the decisions; a decision the PDP did not return, because it stopped early, is a deny
     * @throws AuthZenException if no answer could be obtained, the PDP does not offer the API, or the
     *                          answer does not match the request
     */
    EvaluationsResponse evaluations(EvaluationsRequest request);

    /**
     * Asks the PDP for several decisions in one call (Access Evaluations API) without blocking.
     *
     * @param request the request
     * @return a stage completed with the decisions, or exceptionally with an {@link AuthZenException};
     *         like {@link #evaluateAsync(EvaluationRequest)}, its holder cannot complete it
     * @see #evaluations(EvaluationsRequest)
     */
    CompletionStage<EvaluationsResponse> evaluationsAsync(EvaluationsRequest request);

    /**
     * Asks the PDP which subjects of a type may perform an action on a resource (Subject Search API)
     * and waits for the answer.
     *
     * <p>The answer holds one page of results; when {@link SearchResponse#getNextPageToken()} is
     * present, ask for the next page with {@link SubjectSearchRequest#withPageToken(String)}. A
     * result is not an access decision: enforce access with {@link #evaluate(EvaluationRequest)}.
     *
     * <p>If the PDP publishes metadata without a {@code search_subject_endpoint}, it does not offer
     * this API, and the call fails without contacting it.
     *
     * <p>Searches share the circuit breaker of evaluations. A search that times out counts as a
     * failure like any other, so keep searches fast enough to finish within the request timeout,
     * for example with a page limit, in particular if their filters or limit depend on user input;
     * also rate-limit searches that users can trigger. Otherwise slow searches can open the breaker,
     * and evaluations then fail without contacting the PDP.
     *
     * @param request the request
     * @return one page of subjects
     * @throws AuthZenException if no answer could be obtained, the PDP does not offer the API, or the
     *                          answer is not a valid Search API response
     */
    SearchResponse<Subject> searchSubjects(SubjectSearchRequest request);

    /**
     * Asks the PDP which subjects of a type may perform an action on a resource (Subject Search API)
     * without blocking.
     *
     * @param request the request
     * @return a stage completed with one page of subjects, or exceptionally with an
     *         {@link AuthZenException}; like {@link #evaluateAsync(EvaluationRequest)}, its holder
     *         cannot complete it
     * @see #searchSubjects(SubjectSearchRequest)
     */
    CompletionStage<SearchResponse<Subject>> searchSubjectsAsync(SubjectSearchRequest request);

    /**
     * Asks the PDP on which resources of a type a subject may perform an action (Resource Search
     * API) and waits for the answer.
     *
     * <p>Paging, the metadata and the circuit breaker work as for
     * {@link #searchSubjects(SubjectSearchRequest)}; the metadata parameter is
     * {@code search_resource_endpoint}. A result is not an access decision: enforce access with
     * {@link #evaluate(EvaluationRequest)}.
     *
     * @param request the request
     * @return one page of resources
     * @throws AuthZenException if no answer could be obtained, the PDP does not offer the API, or the
     *                          answer is not a valid Search API response
     */
    SearchResponse<Resource> searchResources(ResourceSearchRequest request);

    /**
     * Asks the PDP on which resources of a type a subject may perform an action (Resource Search
     * API) without blocking.
     *
     * @param request the request
     * @return a stage completed with one page of resources, or exceptionally with an
     *         {@link AuthZenException}; like {@link #evaluateAsync(EvaluationRequest)}, its holder
     *         cannot complete it
     * @see #searchResources(ResourceSearchRequest)
     */
    CompletionStage<SearchResponse<Resource>> searchResourcesAsync(ResourceSearchRequest request);

    /**
     * Asks the PDP which actions a subject may perform on a resource (Action Search API) and waits
     * for the answer.
     *
     * <p>Paging, the metadata and the circuit breaker work as for
     * {@link #searchSubjects(SubjectSearchRequest)}; the metadata parameter is
     * {@code search_action_endpoint}. A result is not an access decision: enforce access with
     * {@link #evaluate(EvaluationRequest)}.
     *
     * @param request the request
     * @return one page of actions
     * @throws AuthZenException if no answer could be obtained, the PDP does not offer the API, or the
     *                          answer is not a valid Search API response
     */
    SearchResponse<Action> searchActions(ActionSearchRequest request);

    /**
     * Asks the PDP which actions a subject may perform on a resource (Action Search API) without
     * blocking.
     *
     * @param request the request
     * @return a stage completed with one page of actions, or exceptionally with an
     *         {@link AuthZenException}; like {@link #evaluateAsync(EvaluationRequest)}, its holder
     *         cannot complete it
     * @see #searchActions(ActionSearchRequest)
     */
    CompletionStage<SearchResponse<Action>> searchActionsAsync(ActionSearchRequest request);

    /**
     * Searches for subjects like {@link #searchSubjects(SubjectSearchRequest)}, but over all pages.
     *
     * <p>The stream is lazy: the first request is sent when a terminal operation starts, and each
     * further page is fetched only once the results before it are consumed, so
     * {@code limit(10)} stops sending requests after the page that holds the tenth result. The
     * stream does not split, so {@code parallel()} does not fetch pages ahead either. Each
     * following request is {@code request} with the PDP's {@code next_token}, as the specification
     * requires; a request that already carries a token starts at that page.
     *
     * <p>Pagination is no snapshot (AuthZEN 1.0 section 8.2): if the data changes while paging, a
     * result can appear twice or not at all. The results are passed on as the PDP returns them,
     * without removing duplicates.
     *
     * <p>A failure while paging, for example an unreachable PDP on the fifth page, is thrown from
     * the stream operation that needed the page, after the results of the earlier pages. The
     * search also fails if it takes more pages or returns more results than the client's
     * {@link Builder#pagination(PaginationOptions) pagination options} allow, or if the PDP hands
     * out a page token a second time. Every page counts on the circuit breaker like a single search.
     *
     * @param request the request for the first page
     * @return a sequential stream of the subjects; it holds no resources, so closing it is optional
     * @throws AuthZenException from the stream's operations if a page could not be obtained, is not
     *                          a valid Search API response, or a limit was reached
     */
    Stream<Subject> searchAllSubjects(SubjectSearchRequest request);

    /**
     * Searches for subjects over all pages with the given limits instead of the client's.
     *
     * @param request the request for the first page
     * @param options the limits of this search
     * @return a sequential stream of the subjects
     * @see #searchAllSubjects(SubjectSearchRequest)
     */
    Stream<Subject> searchAllSubjects(SubjectSearchRequest request, PaginationOptions options);

    /**
     * Searches for subjects over all pages without blocking.
     *
     * <p>Each subscriber gets its own search, which starts when it first requests results. A page
     * is fetched only once the subscriber has requested more results than the earlier pages held,
     * so the subscriber's demand limits the requests sent. Failures and limits are as for
     * {@link #searchAllSubjects(SubjectSearchRequest)}; they reach {@code onError} as an
     * {@link AuthZenException}.
     *
     * <p>Cancelling the subscription stops the search: no further page is requested and nothing
     * more is signalled. A page request that is already sent is not aborted; it runs until the PDP
     * answers or the request timeout passes, and its answer is dropped.
     *
     * <p>The publisher follows the Reactive Streams rules of {@link Flow}. Reactive libraries adapt
     * it directly, for example Reactor with {@code JdkFlowAdapter.flowPublisherToFlux(publisher)}
     * and Mutiny with {@code Multi.createFrom().publisher(publisher)}.
     *
     * @param request the request for the first page
     * @return a publisher of the subjects
     */
    Flow.Publisher<Subject> searchAllSubjectsAsync(SubjectSearchRequest request);

    /**
     * Searches for subjects over all pages without blocking, with the given limits instead of the
     * client's.
     *
     * @param request the request for the first page
     * @param options the limits of this search
     * @return a publisher of the subjects
     * @see #searchAllSubjectsAsync(SubjectSearchRequest)
     */
    Flow.Publisher<Subject> searchAllSubjectsAsync(SubjectSearchRequest request, PaginationOptions options);

    /**
     * Searches for resources like {@link #searchResources(ResourceSearchRequest)}, but over all
     * pages. Paging, failures and limits work as for
     * {@link #searchAllSubjects(SubjectSearchRequest)}.
     *
     * @param request the request for the first page
     * @return a sequential stream of the resources
     */
    Stream<Resource> searchAllResources(ResourceSearchRequest request);

    /**
     * Searches for resources over all pages with the given limits instead of the client's.
     *
     * @param request the request for the first page
     * @param options the limits of this search
     * @return a sequential stream of the resources
     * @see #searchAllResources(ResourceSearchRequest)
     */
    Stream<Resource> searchAllResources(ResourceSearchRequest request, PaginationOptions options);

    /**
     * Searches for resources over all pages without blocking. The publisher works as for
     * {@link #searchAllSubjectsAsync(SubjectSearchRequest)}.
     *
     * @param request the request for the first page
     * @return a publisher of the resources
     */
    Flow.Publisher<Resource> searchAllResourcesAsync(ResourceSearchRequest request);

    /**
     * Searches for resources over all pages without blocking, with the given limits instead of the
     * client's.
     *
     * @param request the request for the first page
     * @param options the limits of this search
     * @return a publisher of the resources
     * @see #searchAllResourcesAsync(ResourceSearchRequest)
     */
    Flow.Publisher<Resource> searchAllResourcesAsync(ResourceSearchRequest request, PaginationOptions options);

    /**
     * Searches for actions like {@link #searchActions(ActionSearchRequest)}, but over all pages.
     * Paging, failures and limits work as for {@link #searchAllSubjects(SubjectSearchRequest)}.
     *
     * @param request the request for the first page
     * @return a sequential stream of the actions
     */
    Stream<Action> searchAllActions(ActionSearchRequest request);

    /**
     * Searches for actions over all pages with the given limits instead of the client's.
     *
     * @param request the request for the first page
     * @param options the limits of this search
     * @return a sequential stream of the actions
     * @see #searchAllActions(ActionSearchRequest)
     */
    Stream<Action> searchAllActions(ActionSearchRequest request, PaginationOptions options);

    /**
     * Searches for actions over all pages without blocking. The publisher works as for
     * {@link #searchAllSubjectsAsync(SubjectSearchRequest)}.
     *
     * @param request the request for the first page
     * @return a publisher of the actions
     */
    Flow.Publisher<Action> searchAllActionsAsync(ActionSearchRequest request);

    /**
     * Searches for actions over all pages without blocking, with the given limits instead of the
     * client's.
     *
     * @param request the request for the first page
     * @param options the limits of this search
     * @return a publisher of the actions
     * @see #searchAllActionsAsync(ActionSearchRequest)
     */
    Flow.Publisher<Action> searchAllActionsAsync(ActionSearchRequest request, PaginationOptions options);

    /**
     * Returns the metadata the PDP publishes, discovering it if needed, and waits for it.
     *
     * @return the metadata, or empty if the PDP publishes none or discovery is disabled
     * @throws AuthZenException if the metadata could not be obtained or is invalid
     * @see Builder#discovery(boolean)
     */
    Optional<PdpMetadata> metadata();

    /**
     * Returns the metadata the PDP publishes, discovering it if needed, without blocking.
     *
     * @return a stage completed with the metadata, empty if the PDP publishes none or discovery is
     *         disabled, or exceptionally with an {@link AuthZenException}
     * @see Builder#discovery(boolean)
     */
    CompletionStage<Optional<PdpMetadata>> metadataAsync();

    /**
     * Returns how many events each listener did not get because its queue was full, for example
     * to expose them as metrics. The counts only grow while the client lives.
     *
     * <p>The default implementation reports no listener, for clients that do not deliver events.
     *
     * @return a snapshot of the counts per listener and event type
     * @see Builder#listener(PepListener)
     */
    default ListenerDrops listenerDrops() {
        return ListenerDrops.none();
    }

    /**
     * Returns a new builder.
     *
     * @return the builder
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link AuthZenClient}. Not thread-safe.
     */
    final class Builder {

        /** Default timeout for establishing a connection. */
        public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

        /** Default timeout for a single request, from sending it until the complete response body is received. */
        public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

        /** Default number of retries after a transient failure. */
        public static final int DEFAULT_MAX_RETRIES = 3;

        /** Highest accepted number of retries. */
        public static final int MAX_RETRIES_LIMIT = 10;

        /** Default number of events each listener's queue holds. */
        public static final int DEFAULT_LISTENER_QUEUE_CAPACITY = 1024;

        /** Smallest accepted listener queue capacity. */
        public static final int MIN_LISTENER_QUEUE_CAPACITY = 16;

        /** Largest accepted listener queue capacity. */
        public static final int MAX_LISTENER_QUEUE_CAPACITY = 1 << 20;

        /** Default number of bytes the events in each listener's queue may retain. */
        public static final long DEFAULT_LISTENER_QUEUE_MAX_BYTES = 64L << 20;

        /** Smallest accepted byte budget of a listener queue, twice the largest PDP response. */
        public static final long MIN_LISTENER_QUEUE_MAX_BYTES = 16L << 20;

        /** Largest accepted byte budget of a listener queue. */
        public static final long MAX_LISTENER_QUEUE_MAX_BYTES = 4L << 30;

        private URI pdpUrl;
        private URI evaluationEndpoint;
        private URI evaluationsEndpoint;
        private URI searchSubjectEndpoint;
        private URI searchResourceEndpoint;
        private URI searchActionEndpoint;
        private CredentialProvider credentials;
        private JsonCodec jsonCodec = JsonCodec.defaultCodec();
        private HttpClient httpClient;
        private Duration connectTimeout;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private boolean discovery = true;
        private DiscoveryOptions discoveryOptions = DiscoveryOptions.defaults();
        private CircuitBreakerOptions circuitBreaker = CircuitBreakerOptions.defaults();
        private PaginationOptions pagination = PaginationOptions.defaults();
        private LongSupplier clock = System::nanoTime;
        private final List<PepListener> listeners = new ArrayList<>();
        private Executor listenerExecutor;
        private int listenerQueueCapacity = DEFAULT_LISTENER_QUEUE_CAPACITY;
        private long listenerQueueMaxBytes = DEFAULT_LISTENER_QUEUE_MAX_BYTES;
        private EventContent eventContent = EventContent.defaults();

        private Builder() {
        }

        /**
         * Sets the base URL of the PDP. Required.
         *
         * <p>It is also the identifier of the PDP: the metadata is fetched from the well-known URL
         * derived from it, for {@code https://pdp.example.com/tenant1} that is
         * {@code https://pdp.example.com/.well-known/authzen-configuration/tenant1}, and its
         * {@code policy_decision_point} must equal this URL. Terminating slashes are removed first.
         * Endpoints the metadata does not list, or all of them if the PDP publishes no metadata,
         * use the default paths of the specification below this URL, such as
         * {@code /access/v1/evaluation}.
         *
         * @param pdpUrl an absolute {@code https} or {@code http} URL without query or fragment
         * @return this builder
         */
        public Builder pdpUrl(String pdpUrl) {
            Objects.requireNonNull(pdpUrl, "pdpUrl must not be null");
            return pdpUrl(URI.create(pdpUrl));
        }

        /**
         * Sets the base URL of the PDP. Required.
         *
         * @param pdpUrl an absolute {@code https} or {@code http} URL without query or fragment
         * @return this builder
         * @see #pdpUrl(String)
         */
        public Builder pdpUrl(URI pdpUrl) {
            this.pdpUrl = Objects.requireNonNull(pdpUrl, "pdpUrl must not be null");
            return this;
        }

        /**
         * Overrides the URL of the Access Evaluation endpoint, for PDPs that publish no metadata
         * and do not serve it at the default path below the PDP URL. The metadata is then not
         * needed for evaluations.
         *
         * @param endpoint an absolute {@code https} or {@code http} URL without query or fragment
         * @return this builder
         */
        public Builder evaluationEndpoint(URI endpoint) {
            this.evaluationEndpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
            return this;
        }

        /**
         * Overrides the URL of the Access Evaluations endpoint, for PDPs that publish no metadata
         * and do not serve it at the default path below the PDP URL. The metadata is then not
         * needed for batch evaluations.
         *
         * <p>It is independent of {@link #evaluationEndpoint(URI)}: overriding only that one leaves
         * batch evaluations on the discovered endpoint, or on the default path below the PDP URL,
         * which may be another host.
         *
         * @param endpoint an absolute {@code https} or {@code http} URL without query or fragment
         * @return this builder
         */
        public Builder evaluationsEndpoint(URI endpoint) {
            this.evaluationsEndpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
            return this;
        }

        /**
         * Overrides the URL of the Subject Search endpoint, for PDPs that publish no metadata and
         * do not serve it at the default path below the PDP URL. Like
         * {@link #evaluationsEndpoint(URI)}, it is independent of the other endpoints.
         *
         * @param endpoint an absolute {@code https} or {@code http} URL without query or fragment
         * @return this builder
         */
        public Builder searchSubjectEndpoint(URI endpoint) {
            this.searchSubjectEndpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
            return this;
        }

        /**
         * Overrides the URL of the Resource Search endpoint, for PDPs that publish no metadata and
         * do not serve it at the default path below the PDP URL. Like
         * {@link #evaluationsEndpoint(URI)}, it is independent of the other endpoints.
         *
         * @param endpoint an absolute {@code https} or {@code http} URL without query or fragment
         * @return this builder
         */
        public Builder searchResourceEndpoint(URI endpoint) {
            this.searchResourceEndpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
            return this;
        }

        /**
         * Overrides the URL of the Action Search endpoint, for PDPs that publish no metadata and
         * do not serve it at the default path below the PDP URL. Like
         * {@link #evaluationsEndpoint(URI)}, it is independent of the other endpoints.
         *
         * @param endpoint an absolute {@code https} or {@code http} URL without query or fragment
         * @return this builder
         */
        public Builder searchActionEndpoint(URI endpoint) {
            this.searchActionEndpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
            return this;
        }

        /**
         * Enables or disables metadata discovery. Enabled by default, with
         * {@link DiscoveryOptions#defaults()}.
         *
         * <p>The last call of this method or {@link #discovery(DiscoveryOptions)} decides. Disabling
         * and enabling again keeps options set earlier.
         *
         * <p>When enabled, the client fetches the metadata the first time it needs an endpoint
         * that is not overridden. The metadata request carries no credentials. Afterwards:
         * <ul>
         *   <li>A {@code 404} means that the PDP publishes no metadata; the default paths are used.</li>
         *   <li>Metadata whose {@code policy_decision_point} differs from the PDP URL, that lacks a
         *       required parameter, or that lists an endpoint that is not an absolute
         *       {@code http(s)} URL without user info, query or fragment, fails the request with a
         *       {@link io.github.authzenenforcer.core.PdpProtocolException}. So does an
         *       {@code http} endpoint for an {@code https} PDP URL, which would send credentials
         *       unencrypted.</li>
         *   <li>Other failures, such as an unreachable PDP, fail the request as well; the next
         *       request tries again.</li>
         * </ul>
         * How long results are kept, and how long a previous result may still be used while the PDP
         * cannot be reached, follows the caching headers of the metadata response within the
         * bounds set with {@link #discovery(DiscoveryOptions)}. Signed metadata
         * ({@code signed_metadata}) is not supported.
         *
         * <p>When disabled, endpoints that are not overridden use the default paths.
         *
         * @param enabled whether to discover endpoints
         * @return this builder
         */
        public Builder discovery(boolean enabled) {
            this.discovery = enabled;
            return this;
        }

        /**
         * Enables metadata discovery with the given cache options. A later
         * {@link #discovery(boolean) discovery(false)} disables it again.
         *
         * @param options how discovered metadata is cached
         * @return this builder
         * @see #discovery(boolean)
         */
        public Builder discovery(DiscoveryOptions options) {
            this.discoveryOptions = Objects.requireNonNull(options, "options must not be null");
            this.discovery = true;
            return this;
        }

        /**
         * Sets how the client stops calling a PDP that keeps failing. Defaults to
         * {@link CircuitBreakerOptions#defaults()}; {@link CircuitBreakerOptions#disabled()} turns
         * the breakers off.
         *
         * @param options the breaker options
         * @return this builder
         * @see CircuitBreakerOptions
         */
        public Builder circuitBreaker(CircuitBreakerOptions options) {
            this.circuitBreaker = Objects.requireNonNull(options, "options must not be null");
            return this;
        }

        /**
         * Sets the limits for searches over all pages, such as
         * {@link AuthZenClient#searchAllSubjects(SubjectSearchRequest)}. Defaults to
         * {@link PaginationOptions#defaults()}; a single search can use other limits.
         *
         * @param options the limits
         * @return this builder
         * @see PaginationOptions
         */
        public Builder pagination(PaginationOptions options) {
            this.pagination = Objects.requireNonNull(options, "options must not be null");
            return this;
        }

        // Lets tests in this package drive the metadata cache and the breakers without waiting
        Builder clock(LongSupplier clock) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            return this;
        }

        /**
         * Sets the provider of the credentials presented to the PDP. Without it, requests carry no
         * {@code Authorization} header.
         *
         * @param credentials the provider
         * @return this builder
         */
        public Builder credentials(CredentialProvider credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
            return this;
        }

        /**
         * Replaces the built-in JSON codec.
         *
         * @param jsonCodec the codec
         * @return this builder
         */
        public Builder jsonCodec(JsonCodec jsonCodec) {
            this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec must not be null");
            return this;
        }

        /**
         * Uses an existing HTTP client, for example to configure TLS client certificates or a proxy.
         *
         * <p>The client must not follow redirects, so that credentials cannot be sent to another host.
         * It cannot be combined with {@link #connectTimeout(Duration)}; configure the timeout on the
         * HTTP client instead.
         *
         * @param httpClient the HTTP client
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
            return this;
        }

        /**
         * Sets the timeout for establishing a connection. Defaults to {@link #DEFAULT_CONNECT_TIMEOUT}.
         *
         * @param connectTimeout a positive duration
         * @return this builder
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
            return this;
        }

        /**
         * Sets the timeout for a single request, from sending it until the complete response body is
         * received. Defaults to {@link #DEFAULT_REQUEST_TIMEOUT}.
         *
         * @param requestTimeout a positive duration
         * @return this builder
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
            return this;
        }

        /**
         * Sets how often a request is repeated after a transient failure. Defaults to
         * {@link #DEFAULT_MAX_RETRIES}; {@code 0} disables retries.
         *
         * <p>AuthZEN requests have no side effects, so repeating them is safe. Only failures that
         * are likely to be transient are retried: connection failures, such as a refused connection,
         * a connect timeout or a connection closed by the server, and HTTP {@code 429},
         * {@code 502}, {@code 503} and {@code 504}. Request timeouts, TLS errors, other statuses and
         * invalid responses fail immediately.
         *
         * <p>Retries wait an exponentially growing, randomized delay of at most two seconds, or as
         * long as a {@code Retry-After} header asks if that is not longer. No retry starts once the
         * request timeout has passed since the first attempt, which bounds the total latency. The
         * single repetition after a {@code 401} does not count as a retry.
         *
         * @param maxRetries between {@code 0} and {@link #MAX_RETRIES_LIMIT}
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0 || maxRetries > MAX_RETRIES_LIMIT) {
                throw new IllegalArgumentException("maxRetries must be between 0 and " + MAX_RETRIES_LIMIT);
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Adds a listener for metrics, tracing or audit trails. Listeners get events in the order
         * they were added, each on its own queue; see {@link PepListener} for the guarantees.
         *
         * @param listener the listener
         * @return this builder
         */
        public Builder listener(PepListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
            return this;
        }

        /**
         * Sets the executor that delivers events to the listeners. By default each listener has
         * its own daemon thread, which ends when it has been idle for a while, so that a listener
         * that blocks holds up no other. On Java 21 or later,
         * {@code Executors.newVirtualThreadPerTaskExecutor()} delivers them on virtual threads.
         *
         * <p>Each listener runs on at most one task at a time, whatever the executor. The client
         * hands tasks to the executor from an internal daemon thread, never from a caller or an
         * HTTP client thread, so an executor that runs tasks inline or blocks in {@code execute}
         * delays only the events. An executor that rejects a task delays the events until the next
         * one is published.
         *
         * @param executor the executor
         * @return this builder
         */
        public Builder listenerExecutor(Executor executor) {
            this.listenerExecutor = Objects.requireNonNull(executor, "executor must not be null");
            return this;
        }

        /**
         * Sets how many events each listener's queue holds. Defaults to
         * {@link #DEFAULT_LISTENER_QUEUE_CAPACITY}. From 80% on, low-priority events are dropped;
         * see {@link PepListener}.
         *
         * @param capacity between {@link #MIN_LISTENER_QUEUE_CAPACITY} and
         *                 {@link #MAX_LISTENER_QUEUE_CAPACITY}
         * @return this builder
         */
        public Builder listenerQueueCapacity(int capacity) {
            if (capacity < MIN_LISTENER_QUEUE_CAPACITY || capacity > MAX_LISTENER_QUEUE_CAPACITY) {
                throw new IllegalArgumentException("listenerQueueCapacity must be between "
                        + MIN_LISTENER_QUEUE_CAPACITY + " and " + MAX_LISTENER_QUEUE_CAPACITY);
            }
            this.listenerQueueCapacity = capacity;
            return this;
        }

        /**
         * Sets how many bytes the events in each listener's queue may retain, approximately.
         * Defaults to {@link #DEFAULT_LISTENER_QUEUE_MAX_BYTES}. An event counts the size of the
         * request and PDP response bodies it carries, plus a small fixed amount; the model objects
         * on the heap take a few times more. From 80% on, low-priority events are dropped, and an
         * event that would exceed the budget is dropped; see {@link PepListener}.
         *
         * @param maxBytes between {@link #MIN_LISTENER_QUEUE_MAX_BYTES} and
         *                 {@link #MAX_LISTENER_QUEUE_MAX_BYTES}
         * @return this builder
         */
        public Builder listenerQueueMaxBytes(long maxBytes) {
            if (maxBytes < MIN_LISTENER_QUEUE_MAX_BYTES || maxBytes > MAX_LISTENER_QUEUE_MAX_BYTES) {
                throw new IllegalArgumentException("listenerQueueMaxBytes must be between "
                        + MIN_LISTENER_QUEUE_MAX_BYTES + " and " + MAX_LISTENER_QUEUE_MAX_BYTES);
            }
            this.listenerQueueMaxBytes = maxBytes;
            return this;
        }

        /**
         * Sets which values of requests and responses the listeners see masked. Defaults to
         * {@link EventContent#defaults()}, which masks personal data the way the
         * {@code toString()} of the model types does.
         *
         * @param content the content
         * @return this builder
         */
        public Builder eventContent(EventContent content) {
            this.eventContent = Objects.requireNonNull(content, "content must not be null");
            return this;
        }

        /**
         * Builds the client.
         *
         * @return the client
         * @throws IllegalStateException    if the PDP URL is not set, both an HTTP client and a
         *                                  connect timeout are set, or the HTTP client follows redirects
         * @throws IllegalArgumentException if a URL is not an absolute {@code http(s)} URL without
         *                                  user info, query or fragment
         */
        public AuthZenClient build() {
            if (pdpUrl == null) {
                throw new IllegalStateException("pdpUrl is required");
            }
            if (httpClient != null && connectTimeout != null) {
                throw new IllegalStateException("connectTimeout cannot be combined with a custom httpClient");
            }
            HttpClient client = httpClient;
            if (client == null) {
                client = HttpClient.newBuilder()
                        .connectTimeout(connectTimeout != null ? connectTimeout : DEFAULT_CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
            } else if (client.followRedirects() != HttpClient.Redirect.NEVER) {
                throw new IllegalStateException("httpClient must not follow redirects");
            }
            EndpointOverrides overrides = new EndpointOverrides(evaluationEndpoint, evaluationsEndpoint,
                    searchSubjectEndpoint, searchResourceEndpoint, searchActionEndpoint);
            return new DefaultAuthZenClient(pdpUrl, overrides, credentials, jsonCodec, client, requestTimeout,
                    maxRetries, discovery ? discoveryOptions : null, circuitBreaker, pagination, clock,
                    EventDispatcher.create(listeners, listenerExecutor, listenerQueueCapacity,
                            listenerQueueMaxBytes, eventContent));
        }

        private static Duration requirePositive(Duration duration, String name) {
            Objects.requireNonNull(duration, () -> name + " must not be null");
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return duration;
        }
    }
}
