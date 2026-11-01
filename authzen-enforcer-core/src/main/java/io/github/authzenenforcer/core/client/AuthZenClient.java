package io.github.authzenenforcer.core.client;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.internal.client.DefaultAuthZenClient;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
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

        /** Default timeout for a single request, from sending it until the response is received. */
        public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

        private URI pdpUrl;
        private URI evaluationEndpoint;
        private CredentialProvider credentials;
        private JsonCodec jsonCodec = JsonCodec.defaultCodec();
        private HttpClient httpClient;
        private Duration connectTimeout;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

        private Builder() {
        }

        /**
         * Sets the base URL of the PDP. Required.
         *
         * <p>Endpoints are derived from it with the default paths of the specification, such as
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
         * Overrides the URL of the Access Evaluation endpoint, for PDPs that do not serve it at the
         * default path below the PDP URL.
         *
         * @param endpoint an absolute {@code https} or {@code http} URL without query or fragment
         * @return this builder
         */
        public Builder evaluationEndpoint(URI endpoint) {
            this.evaluationEndpoint = Objects.requireNonNull(endpoint, "endpoint");
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
         * Sets the timeout for a single request. Defaults to {@link #DEFAULT_REQUEST_TIMEOUT}.
         *
         * @param requestTimeout a positive duration
         * @return this builder
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
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
            return new DefaultAuthZenClient(pdpUrl, evaluationEndpoint, credentials, jsonCodec, client, requestTimeout);
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
