package io.github.authzenenforcer.core.internal.http;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpAuthenticationException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.internal.json.JsonParser;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Sends AuthZEN requests to the PDP and turns every failure into an {@link AuthZenException}.
 *
 * <p>Per request it adds a fresh {@code X-Request-ID}, presents the credential, repeats the request
 * once after a {@code 401}, checks the echoed request id and limits the response size. Redirects are
 * never followed, so credentials cannot leak to another host.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class PdpTransport {

    /** Request id header recommended by the specification. */
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    /** Maximum response body size in bytes, matching the parser's input limit. */
    static final int MAX_RESPONSE_BYTES = JsonParser.DEFAULT_MAX_LENGTH;

    private static final System.Logger LOGGER = System.getLogger(PdpTransport.class.getName());
    private static final String JSON = "application/json";
    private static final String USER_AGENT = userAgent();

    private final HttpClient httpClient;
    private final CredentialProvider credentials;
    private final JsonCodec codec;
    private final Duration requestTimeout;

    /**
     * Creates a transport.
     *
     * @param httpClient     the HTTP client; it must not follow redirects
     * @param credentials    the credential provider, or {@code null} to send no credentials
     * @param codec          the JSON codec
     * @param requestTimeout the timeout of a single HTTP exchange
     */
    public PdpTransport(HttpClient httpClient, CredentialProvider credentials, JsonCodec codec,
            Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.credentials = credentials;
        this.codec = Objects.requireNonNull(codec, "codec");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    /**
     * Posts a JSON tree and returns the parsed response body.
     *
     * @param endpoint the endpoint URL
     * @param body     the request body as a JSON tree
     * @return a future completed with the parsed response, or exceptionally with an
     *         {@link AuthZenException}
     */
    public CompletableFuture<Object> post(URI endpoint, Object body) {
        String json;
        try {
            json = codec.write(body);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new AuthZenException("Request cannot be serialized to JSON", e));
        }
        return send(endpoint, json, false);
    }

    private CompletableFuture<Object> send(URI endpoint, String json, boolean retried) {
        // Every HTTP exchange, including the retry, gets its own request id
        Exchange exchange = new Exchange(endpoint, json, UUID.randomUUID().toString());
        AccessCredential credential;
        HttpRequest request;
        try {
            credential = currentCredential();
            request = exchange.request(credential);
        } catch (AuthZenException e) {
            return CompletableFuture.failedFuture(e);
        }
        long start = System.nanoTime();
        return httpClient.sendAsync(request, new LimitedBodyHandler(MAX_RESPONSE_BYTES))
                .handle((response, error) -> {
                    long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
                    if (error != null) {
                        throw new CompletionException(transportFailure(exchange, unwrap(error)));
                    }
                    LOGGER.log(Level.DEBUG, () -> "PDP request " + exchange.requestId + " to " + exchange.endpoint
                            + " returned HTTP " + response.statusCode() + " in " + elapsedMillis + " ms");
                    return response;
                })
                .thenCompose(response -> {
                    if (response.statusCode() == 401 && credential != null && !retried) {
                        LOGGER.log(Level.DEBUG, () -> "PDP request " + exchange.requestId
                                + " was rejected with HTTP 401; refreshing the credential and retrying once");
                        try {
                            credentials.invalidate(credential);
                        } catch (RuntimeException e) {
                            return CompletableFuture.failedFuture(
                                    new AuthZenException("Credential provider failed to invalidate the credential", e));
                        }
                        return send(endpoint, json, true);
                    }
                    return CompletableFuture.completedFuture(handle(exchange, response));
                });
    }

    private AccessCredential currentCredential() {
        if (credentials == null) {
            return null;
        }
        AccessCredential credential;
        try {
            credential = credentials.getCredential();
        } catch (RuntimeException e) {
            throw new AuthZenException("Credential provider failed to supply a credential", e);
        }
        if (credential == null) {
            throw new AuthZenException("Credential provider returned no credential");
        }
        return credential;
    }

    private Object handle(Exchange exchange, HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new PdpAuthenticationException("PDP rejected the credentials of the PEP with HTTP " + status
                    + exchange.suffix(), status);
        }
        if (status >= 500) {
            throw new PdpCommunicationException("PDP returned HTTP " + status + exchange.suffix());
        }
        if (status != 200) {
            throw new PdpProtocolException("PDP returned unexpected HTTP " + status + exchange.suffix());
        }
        Optional<String> echoed = response.headers().firstValue(REQUEST_ID_HEADER);
        // Echoing through this header is only recommended, but a different value means a mixed-up response
        if (echoed.isPresent() && !echoed.get().equals(exchange.requestId)) {
            throw new PdpProtocolException("PDP response carries a different " + REQUEST_ID_HEADER + exchange.suffix());
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(response.body()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new PdpProtocolException("PDP response is not valid UTF-8" + exchange.suffix(), e);
        }
        try {
            return codec.read(text);
        } catch (RuntimeException e) {
            throw new PdpProtocolException("PDP response is not valid JSON" + exchange.suffix(), e);
        }
    }

    private AuthZenException transportFailure(Exchange exchange, Throwable error) {
        if (error instanceof AuthZenException authZenException) {
            return authZenException;
        }
        if (error instanceof LimitedBodyHandler.BodyTooLargeException) {
            return new PdpProtocolException("PDP response exceeds " + MAX_RESPONSE_BYTES + " bytes" + exchange.suffix(), error);
        }
        if (error instanceof HttpTimeoutException) {
            return new PdpCommunicationException("PDP did not respond in time" + exchange.suffix(), error);
        }
        if (error instanceof IOException) {
            return new PdpCommunicationException("PDP request failed" + exchange.suffix(), error);
        }
        return new AuthZenException("PDP request failed unexpectedly" + exchange.suffix(), error);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String userAgent() {
        String version = PdpTransport.class.getPackage().getImplementationVersion();
        return version == null ? "authzen-enforcer" : "authzen-enforcer/" + version;
    }

    /** A single HTTP exchange with the PDP. */
    private final class Exchange {

        private final URI endpoint;
        private final String json;
        private final String requestId;

        Exchange(URI endpoint, String json, String requestId) {
            this.endpoint = endpoint;
            this.json = json;
            this.requestId = requestId;
        }

        HttpRequest request(AccessCredential credential) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(requestTimeout)
                    .header("Content-Type", JSON)
                    .header("Accept", JSON)
                    .header("User-Agent", USER_AGENT)
                    .header(REQUEST_ID_HEADER, requestId)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (credential instanceof AccessCredential.Bearer bearer) {
                builder.header("Authorization", "Bearer " + bearer.getToken());
            }
            return builder.build();
        }

        String suffix() {
            return " (" + REQUEST_ID_HEADER + " " + requestId + ")";
        }
    }
}
