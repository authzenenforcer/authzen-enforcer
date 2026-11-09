package io.github.authzenenforcer.core.internal.http;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpAuthenticationException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.event.AttemptCompleted;
import io.github.authzenenforcer.core.event.TraceContext;
import io.github.authzenenforcer.core.internal.event.CallTrace;
import io.github.authzenenforcer.core.internal.event.TracerHook;
import io.github.authzenenforcer.core.internal.json.JsonParser;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Sends AuthZEN requests to the PDP and turns every failure into an {@link AuthZenException}.
 *
 * <p>Per exchange it adds a fresh {@code X-Request-ID} and, when a tracer traces the call, the
 * trace context, and presents the current credential. After a {@code 401} it refreshes the
 * credential and repeats the request once; transient failures are repeated as the
 * {@link RetryPolicy} allows. It checks the echoed request id and limits the
 * response size. Redirects are never followed, so credentials cannot leak to another host.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class PdpTransport {

    /** Request id header recommended by the specification. */
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    /** The W3C Trace Context header with the parent span, sent when a tracer traces the call. */
    public static final String TRACEPARENT_HEADER = "traceparent";

    /** The W3C Trace Context header with vendor state, sent along with {@link #TRACEPARENT_HEADER}. */
    public static final String TRACESTATE_HEADER = "tracestate";

    /** Maximum response body size in bytes, matching the parser's input limit. */
    static final int MAX_RESPONSE_BYTES = JsonParser.DEFAULT_MAX_LENGTH;

    // Longer timeouts would overflow the deadline arithmetic; they are treated as this one, about 146 years
    private static final Duration MAX_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE / 2);

    private static final System.Logger LOGGER = System.getLogger(PdpTransport.class.getName());
    private static final String JSON = "application/json";
    private static final String USER_AGENT = userAgent();

    private final HttpClient httpClient;
    private final CredentialProvider credentials;
    private final JsonCodec codec;
    private final Duration requestTimeout;
    private final long requestTimeoutNanos;
    private final CredentialWaits credentialWaits = new CredentialWaits();
    private final RetryPolicy retryPolicy;

    /**
     * Creates a transport.
     *
     * @param httpClient     the HTTP client; it must not follow redirects
     * @param credentials    the credential provider, or {@code null} to send no credentials
     * @param codec          the JSON codec
     * @param requestTimeout the timeout of a single HTTP exchange, including reading the response
     *                       body, also the time after the first attempt within which retries may start
     * @param maxRetries     how often a request that failed transiently may be repeated
     */
    public PdpTransport(HttpClient httpClient, CredentialProvider credentials, JsonCodec codec,
            Duration requestTimeout, int maxRetries) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.credentials = credentials;
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        this.requestTimeoutNanos = requestTimeout.compareTo(MAX_TIMEOUT) > 0
                ? MAX_TIMEOUT.toNanos()
                : requestTimeout.toNanos();
        this.retryPolicy = new RetryPolicy(maxRetries, requestTimeout, Clock.systemUTC());
    }

    /**
     * Posts a JSON tree and returns the parsed response body.
     *
     * @param endpoint the endpoint URL
     * @param body     the request body as a JSON tree
     * @param trace        records every HTTP exchange
     * @param cancellation stops the request once the caller cancels it
     * @return a future completed with the parsed response, exceptionally with an
     *         {@link AuthZenException}, or with a {@link CancellationException} once cancelled
     */
    public CompletableFuture<Object> post(URI endpoint, Object body, CallTrace trace, Cancellation cancellation) {
        String json;
        try {
            json = codec.write(body);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new AuthZenException("Request cannot be serialized to JSON", e));
        }
        if (json == null) {
            return CompletableFuture.failedFuture(new AuthZenException("JSON codec returned no text for the request"));
        }
        trace.requestBytes(json.length());
        return send(new Call(endpoint, false, json, System.nanoTime(), trace, cancellation), 0, false);
    }

    /**
     * A document fetched with {@link #get(URI, CallTrace)}.
     *
     * @param body    the parsed document, empty if the PDP answered {@code 404}
     * @param headers the response headers, for example for caching directives
     */
    public record Document(Optional<Object> body, HttpHeaders headers) {
    }

    /**
     * Gets a JSON document without presenting credentials, as for the PDP metadata.
     *
     * @param endpoint the document URL
     * @param trace    records every HTTP exchange
     * @return a future completed with the document, or exceptionally with an {@link AuthZenException}
     */
    public CompletableFuture<Document> get(URI endpoint, CallTrace trace) {
        return send(new Call(endpoint, true, null, System.nanoTime(), trace, new Cancellation()), 0, false)
                .thenApply(Document.class::cast);
    }

    private CompletableFuture<Object> send(Call call, int retries, boolean refreshed) {
        // Neither a retry nor the repeat after a 401 starts once the caller cancelled
        if (call.cancellation.isCancelled()) {
            return CompletableFuture.failedFuture(cancelled());
        }
        // Every HTTP exchange, including a repeated one, gets its own request id
        Exchange exchange = new Exchange(call.endpoint, call.get, call.json, UUID.randomUUID().toString());
        // The request timeout covers supplying the credential as well
        long deadline = System.nanoTime() + requestTimeoutNanos;
        CompletableFuture<AccessCredential> credential;
        try {
            credential = exchange.isGet() ? CompletableFuture.completedFuture(null) : currentCredential(call, deadline);
        } catch (AuthZenException e) {
            return CompletableFuture.failedFuture(e);
        }
        return credential.thenCompose(current -> send(call, retries, refreshed, exchange, current, deadline));
    }

    private CompletableFuture<Object> send(Call call, int retries, boolean refreshed, Exchange exchange,
            AccessCredential credential, long deadline) {
        Duration timeout = Duration.ofNanos(deadline - System.nanoTime());
        if (timeout.isZero() || timeout.isNegative()) {
            return CompletableFuture.failedFuture(credentialTimeout());
        }
        // A credential that arrives after the caller cancelled is not sent
        if (call.cancellation.isCancelled()) {
            return CompletableFuture.failedFuture(cancelled());
        }
        TracerHook.TracedExchange traced;
        HttpRequest request;
        try {
            traced = call.trace.startExchange(exchange.method(), exchange.endpoint, exchange.requestId);
            request = exchange.request(credential, timeout, traced != null ? traced.context() : null);
        } catch (AuthZenException e) {
            return CompletableFuture.failedFuture(e);
        } catch (RuntimeException e) {
            // Not expected, since every value is validated; failing here keeps every path the same
            // instead of relying on how each caller handles an exception thrown at it
            return CompletableFuture.failedFuture(
                    new AuthZenException("Preparing the PDP request failed" + exchange.suffix(), e));
        }
        Instant startTime = call.trace.isActive() ? Instant.now() : null;
        long start = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> pending = call.cancellation.track(httpClient.sendAsync(request,
                new LimitedBodyHandler(MAX_RESPONSE_BYTES)));
        // The JDK stops the request timer once the headers arrive, so bound the body as well and
        // abort the exchange when the deadline passes
        CompletableFuture<HttpResponse<byte[]>> bounded = pending.copy()
                .orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
        bounded.whenComplete((response, error) -> {
            if (error instanceof TimeoutException) {
                pending.cancel(true);
            }
        });
        return bounded
                .handle((response, error) -> {
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                    AttemptCompleted.Builder attempt = call.trace.isActive()
                            ? exchange.attempt(startTime, elapsed, traced)
                            : null;
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        LOGGER.log(Level.DEBUG, () -> "PDP request " + exchange.requestId + " to " + exchange.endpoint
                                + " failed after " + elapsed.toMillis() + " ms: " + cause);
                        if (cause instanceof TimeoutException) {
                            // Completed on the JDK's single delay thread, which every timeout in the
                            // JVM shares; whatever follows, the caller's code included, runs elsewhere
                            return CompletableFuture.supplyAsync(
                                    () -> afterError(call, retries, refreshed, exchange, attempt, cause))
                                    .thenCompose(Function.identity());
                        }
                        return afterError(call, retries, refreshed, exchange, attempt, cause);
                    }
                    LOGGER.log(Level.DEBUG, () -> "PDP request " + exchange.requestId + " to " + exchange.endpoint
                            + " returned HTTP " + response.statusCode() + " in " + elapsed.toMillis() + " ms");
                    if (attempt != null) {
                        attempt.status(response.statusCode());
                    }
                    byte[] body = response.body();
                    call.trace.responseBytes(body != null ? body.length : 0);
                    return onResponse(call, retries, refreshed, exchange, credential, response, attempt);
                })
                .thenCompose(Function.identity());
    }

    private CompletableFuture<Object> afterError(Call call, int retries, boolean refreshed, Exchange exchange,
            AttemptCompleted.Builder attempt, Throwable cause) {
        if (call.cancellation.isCancelled()) {
            record(call, attempt, builder -> builder.failure(cause), Optional.empty());
            return CompletableFuture.failedFuture(cancelled());
        }
        Optional<Duration> delay = retryPolicy.afterError(retries, call.elapsed(), cause);
        record(call, attempt, builder -> builder.failure(cause), delay);
        return delay
                .map(wait -> retry(call, retries, refreshed, wait, exchange, cause.toString()))
                .orElseGet(() -> CompletableFuture.failedFuture(transportFailure(exchange, cause)));
    }

    private CompletableFuture<Object> onResponse(Call call, int retries, boolean refreshed, Exchange exchange,
            AccessCredential credential, HttpResponse<byte[]> response, AttemptCompleted.Builder attempt) {
        if (response.statusCode() == 401 && credential != null && !refreshed) {
            LOGGER.log(Level.DEBUG, () -> "PDP request " + exchange.requestId
                    + " was rejected with HTTP 401; refreshing the credential and retrying once");
            try {
                credentials.invalidate(credential);
            } catch (RuntimeException e) {
                record(call, attempt, builder -> builder, Optional.empty());
                return CompletableFuture.failedFuture(
                        new AuthZenException("Credential provider failed to invalidate the credential", e));
            }
            record(call, attempt, AttemptCompleted.Builder::refreshCredential, Optional.empty());
            return send(call, retries, true);
        }
        Optional<Duration> delay = retryPolicy.afterResponse(retries, call.elapsed(), response);
        record(call, attempt, builder -> builder, delay);
        if (delay.isPresent()) {
            return retry(call, retries, refreshed, delay.get(), exchange, "HTTP " + response.statusCode());
        }
        try {
            return CompletableFuture.completedFuture(handle(exchange, response));
        } catch (AuthZenException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // Recorded before the next exchange starts, so that the trace keeps the order of the exchanges
    private static void record(Call call, AttemptCompleted.Builder attempt,
            Function<AttemptCompleted.Builder, AttemptCompleted.Builder> outcome, Optional<Duration> delay) {
        if (attempt == null) {
            return;
        }
        AttemptCompleted.Builder completed = outcome.apply(attempt);
        delay.ifPresent(completed::retry);
        call.trace.attempt(completed);
    }

    private CompletableFuture<Object> retry(Call call, int retries, boolean refreshed, Duration delay,
            Exchange exchange, String reason) {
        LOGGER.log(Level.DEBUG, () -> "Repeating PDP request " + exchange.requestId + " after " + reason
                + " in " + delay.toMillis() + " ms (retry " + (retries + 1) + ")");
        Executor delayed = CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS);
        return call.cancellation.track(CompletableFuture.supplyAsync(() -> null, delayed))
                .thenCompose(ignored -> send(call, retries + 1, refreshed));
    }

    private CompletableFuture<AccessCredential> currentCredential(Call call, long deadline) {
        if (credentials == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletionStage<AccessCredential> stage;
        try {
            stage = credentials.getCredentialAsync();
        } catch (RuntimeException e) {
            throw new AuthZenException("Credential provider failed to supply a credential", e);
        }
        if (stage == null) {
            throw new AuthZenException("Credential provider returned no credential");
        }
        // A stage that is already complete needs no waiting; a sharable one gets a single callback.
        // Only a plain CompletableFuture is asked: subclasses such as minimalCompletionStage() refuse isDone()
        CompletableFuture<Object> waiter = stage instanceof CompletableFuture<?> done
                && done.getClass() == CompletableFuture.class && done.isDone()
                ? done.thenApply(Object.class::cast)
                : credentialWaits.waitFor(stage);
        if (!waiter.isDone()) {
            // Completes only this request's waiter, never the provider's stage
            call.cancellation.track(waiter).orTimeout(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        }
        return waiter.handle((value, error) -> credential(call, value, error, deadline))
                .thenCompose(Function.identity());
    }

    private CompletableFuture<AccessCredential> credential(Call call, Object value, Throwable error, long deadline) {
        if (error != null) {
            Throwable cause = unwrap(error);
            // A timeout of the provider's own fails like any other provider failure
            if (cause instanceof TimeoutException && System.nanoTime() - deadline >= 0) {
                // Leaves the JDK's single delay thread, as for the timeout of an exchange, also if
                // the caller cancelled meanwhile
                return CompletableFuture.supplyAsync(() -> {
                    throw new CompletionException(call.cancellation.isCancelled() ? cancelled() : credentialTimeout());
                });
            }
            if (call.cancellation.isCancelled()) {
                return CompletableFuture.failedFuture(cancelled());
            }
            return CompletableFuture.failedFuture(
                    new AuthZenException("Credential provider failed to supply a credential", cause));
        }
        if (value == null) {
            return CompletableFuture.failedFuture(new AuthZenException("Credential provider returned no credential"));
        }
        if (!(value instanceof AccessCredential credential)) {
            // Only reachable through unchecked casts; the class name is safe to show, the value is not
            return CompletableFuture.failedFuture(new AuthZenException(
                    "Credential provider supplied a " + value.getClass().getName() + " instead of a credential"));
        }
        return CompletableFuture.completedFuture(credential);
    }

    private static CancellationException cancelled() {
        return new CancellationException("The caller cancelled the PDP request");
    }

    private AuthZenException credentialTimeout() {
        return new AuthZenException("Credential provider supplied no credential within the request timeout of "
                + Duration.ofNanos(requestTimeoutNanos).toMillis() + " ms");
    }

    private Object handle(Exchange exchange, HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            String reason = exchange.isGet() ? "PDP requires authentication for " + exchange.endpoint + " with HTTP "
                    : "PDP rejected the credentials of the PEP with HTTP ";
            throw new PdpAuthenticationException(reason + status + exchange.suffix(), status);
        }
        if (status == 404 && exchange.isGet()) {
            checkRequestId(exchange, response);
            return new Document(Optional.empty(), response.headers());
        }
        if (status >= 500) {
            throw new PdpCommunicationException("PDP returned HTTP " + status + exchange.suffix());
        }
        if (status == 429) {
            // An overloaded PDP, like a 503, not a protocol violation
            throw new PdpCommunicationException("PDP kept rate-limiting with HTTP 429" + exchange.suffix());
        }
        if (status != 200) {
            throw new PdpProtocolException("PDP returned unexpected HTTP " + status + exchange.suffix());
        }
        checkRequestId(exchange, response);
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
        Object body;
        try {
            body = codec.read(text);
        } catch (RuntimeException e) {
            throw new PdpProtocolException("PDP response is not valid JSON" + exchange.suffix(), e);
        }
        if (!exchange.isGet()) {
            return body;
        }
        // An empty document means 404, so a JSON null must not become one
        if (body == null) {
            throw new PdpProtocolException("PDP document is JSON null" + exchange.suffix());
        }
        return new Document(Optional.of(body), response.headers());
    }

    // Echoing through this header is only recommended, but a different value means a mixed-up response
    private static void checkRequestId(Exchange exchange, HttpResponse<byte[]> response) {
        Optional<String> echoed = response.headers().firstValue(REQUEST_ID_HEADER);
        if (echoed.isPresent() && !echoed.get().equals(exchange.requestId)) {
            throw new PdpProtocolException("PDP response carries a different " + REQUEST_ID_HEADER + exchange.suffix());
        }
    }

    private AuthZenException transportFailure(Exchange exchange, Throwable error) {
        if (error instanceof AuthZenException authZenException) {
            return authZenException;
        }
        if (error instanceof LimitedBodyHandler.BodyTooLargeException) {
            return new PdpProtocolException("PDP response exceeds " + MAX_RESPONSE_BYTES + " bytes" + exchange.suffix(), error);
        }
        if (error instanceof HttpTimeoutException || error instanceof TimeoutException) {
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

    /** One logical request, which may take several HTTP exchanges: a GET, or a POST of {@code json}. */
    private record Call(URI endpoint, boolean get, String json, long startNanos, CallTrace trace,
            Cancellation cancellation) {

        Duration elapsed() {
            return Duration.ofNanos(System.nanoTime() - startNanos);
        }
    }

    /** A single HTTP exchange with the PDP. */
    private final class Exchange {

        private final URI endpoint;
        private final boolean get;
        private final String json;
        private final String requestId;

        Exchange(URI endpoint, boolean get, String json, String requestId) {
            this.endpoint = endpoint;
            this.get = get;
            this.json = json;
            this.requestId = requestId;
        }

        boolean isGet() {
            return get;
        }

        String method() {
            return get ? "GET" : "POST";
        }

        HttpRequest request(AccessCredential credential, Duration timeout, TraceContext trace) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Accept", JSON)
                    .header("User-Agent", USER_AGENT)
                    .header(REQUEST_ID_HEADER, requestId);
            if (isGet()) {
                builder.GET();
            } else {
                builder.header("Content-Type", JSON)
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            }
            if (credential instanceof AccessCredential.Bearer bearer) {
                builder.header("Authorization", "Bearer " + bearer.getToken());
            }
            if (trace != null) {
                builder.header(TRACEPARENT_HEADER, trace.getTraceparent());
                trace.getTracestate().ifPresent(state -> builder.header(TRACESTATE_HEADER, state));
            }
            return builder.build();
        }

        AttemptCompleted.Builder attempt(Instant startTime, Duration duration, TracerHook.TracedExchange traced) {
            AttemptCompleted.Builder attempt = AttemptCompleted.builder()
                    .endpoint(endpoint)
                    .method(method())
                    .requestId(requestId)
                    .startTime(startTime)
                    .duration(duration);
            return traced != null ? attempt.exchangeSpan(traced.span()) : attempt;
        }

        String suffix() {
            return " (" + REQUEST_ID_HEADER + " " + requestId + ")";
        }
    }
}
