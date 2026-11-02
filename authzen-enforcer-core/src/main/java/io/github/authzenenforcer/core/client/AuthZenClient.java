package io.github.authzenenforcer.core.client;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.internal.client.DefaultAuthZenClient;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
     * @param request the request
     * @return a future completed with the decision, or exceptionally with an {@link AuthZenException}
     *         (wrapped in a {@link java.util.concurrent.CompletionException} by dependent stages)
     */
    CompletableFuture<EvaluationResponse> evaluateAsync(EvaluationRequest request);

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
     * @return a future completed with the metadata, empty if the PDP publishes none or discovery is
     *         disabled, or exceptionally with an {@link AuthZenException}
     * @see Builder#discovery(boolean)
     */
    CompletableFuture<Optional<PdpMetadata>> metadataAsync();

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

        /** Default time discovered metadata is used before it is fetched again. */
        public static final Duration DEFAULT_METADATA_REFRESH_INTERVAL = Duration.ofHours(1);

        private URI pdpUrl;
        private URI evaluationEndpoint;
        private CredentialProvider credentials;
        private JsonCodec jsonCodec = JsonCodec.defaultCodec();
        private HttpClient httpClient;
        private Duration connectTimeout;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private boolean discovery = true;
        private Duration metadataRefreshInterval = DEFAULT_METADATA_REFRESH_INTERVAL;

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
         * Enables or disables metadata discovery. Enabled by default.
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
         * Results, including a {@code 404}, are kept for {@link #metadataRefreshInterval(Duration)}.
         * A {@code 404} that replaces published metadata is checked again after at most a minute,
         * since it may come from a proxy during a rollout. If a refresh fails because the PDP
         * cannot be reached, reports a server error or keeps rate-limiting, the previous result
         * stays in use and the refresh is retried after at most a minute. HTTP caching headers of the metadata
         * response are ignored. Signed metadata ({@code signed_metadata}) is not supported.
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
         * Sets how long discovered metadata is used before it is fetched again. Defaults to
         * {@link #DEFAULT_METADATA_REFRESH_INTERVAL}.
         *
         * @param refreshInterval a positive duration
         * @return this builder
         * @see #discovery(boolean)
         */
        public Builder metadataRefreshInterval(Duration refreshInterval) {
            this.metadataRefreshInterval = requirePositive(refreshInterval, "metadataRefreshInterval");
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
            return new DefaultAuthZenClient(pdpUrl, evaluationEndpoint, credentials, jsonCodec, client, requestTimeout,
                    maxRetries, discovery, metadataRefreshInterval);
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
