package io.github.authzenenforcer.core.client;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

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
        private LongSupplier clock = System::nanoTime;

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
            Objects.requireNonNull(pdpUrl, "pdpUrl");
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
            this.pdpUrl = Objects.requireNonNull(pdpUrl, "pdpUrl");
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
            this.evaluationEndpoint = Objects.requireNonNull(endpoint, "endpoint");
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
            this.evaluationsEndpoint = Objects.requireNonNull(endpoint, "endpoint");
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
            this.searchSubjectEndpoint = Objects.requireNonNull(endpoint, "endpoint");
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
            this.searchResourceEndpoint = Objects.requireNonNull(endpoint, "endpoint");
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
            this.searchActionEndpoint = Objects.requireNonNull(endpoint, "endpoint");
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
            this.discoveryOptions = Objects.requireNonNull(options, "options");
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
            this.circuitBreaker = Objects.requireNonNull(options, "options");
            return this;
        }

        // Lets tests in this package drive the metadata cache and the breakers without waiting
        Builder clock(LongSupplier clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
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
            this.credentials = Objects.requireNonNull(credentials, "credentials");
            return this;
        }

        /**
         * Replaces the built-in JSON codec.
         *
         * @param jsonCodec the codec
         * @return this builder
         */
        public Builder jsonCodec(JsonCodec jsonCodec) {
            this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
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
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
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
                    maxRetries, discovery ? discoveryOptions : null, circuitBreaker, clock);
        }

        private static Duration requirePositive(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return duration;
        }
    }
}
