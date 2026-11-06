package io.github.authzenenforcer.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.event.AttemptCompleted;
import io.github.authzenenforcer.core.event.CallCompleted;
import io.github.authzenenforcer.core.event.ExchangeStart;
import io.github.authzenenforcer.core.event.Operation;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.spi.PepTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenTelemetryTracingTest {

    private static final EvaluationRequest REQUEST = EvaluationRequest.of(Subject.of("user", "alice@example.com"),
            Action.of("can_read"), Resource.of("account", "123"));
    private static final URI ENDPOINT = URI.create("https://pdp.example.com/access/v1/evaluation");

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    private final OpenTelemetry openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();

    private HttpServer server;
    private final List<String> traceparents = new CopyOnWriteArrayList<>();
    private final List<String> requestIds = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startPdp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Fails the first request, so that the client retries
        server.createContext("/access/v1/evaluation", exchange -> {
            exchange.getRequestBody().readAllBytes();
            traceparents.add(String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")));
            requestIds.add(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            boolean first = requests.incrementAndGet() == 1;
            byte[] body = (first ? "" : "{\"decision\": false}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(first ? 503 : 200, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopPdp() {
        server.stop(0);
        provider.close();
    }

    private AuthZenClient client(OpenTelemetryTracing tracing) {
        return AuthZenClient.builder()
                .pdpUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .discovery(false)
                .requestTimeout(Duration.ofSeconds(2))
                .tracer(tracing)
                .build();
    }

    private List<SpanData> awaitSpans(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (exporter.getFinishedSpanItems().size() < count && System.nanoTime() - deadline < 0) {
            Thread.sleep(5);
        }
        return exporter.getFinishedSpanItems();
    }

    private static SpanData named(List<SpanData> spans, String name) {
        return spans.stream().filter(span -> span.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void recordsTheCallAndEachExchangeWhereThePdpCanFindThem() throws InterruptedException {
        Span parent = openTelemetry.getTracer("test").spanBuilder("GET /documents/42").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            assertFalse(client(OpenTelemetryTracing.builder(openTelemetry).build()).evaluate(REQUEST).getDecision());
        } finally {
            parent.end();
        }

        List<SpanData> spans = awaitSpans(4);
        SpanData call = named(spans, "authzen evaluation");
        assertEquals(SpanKind.INTERNAL, call.getKind());
        assertEquals(parent.getSpanContext().getSpanId(), call.getParentSpanId());
        assertEquals("deny", call.getAttributes().get(AttributeKey.stringKey("authzen.outcome")));
        assertEquals("account", call.getAttributes().get(AttributeKey.stringKey("authzen.resource.type")));
        assertEquals("can_read", call.getAttributes().get(AttributeKey.stringKey("authzen.action.name")));

        List<SpanData> exchanges = spans.stream().filter(span -> span.getKind() == SpanKind.CLIENT)
                .sorted(Comparator.comparing(SpanData::getStartEpochNanos))
                .toList();
        assertEquals(2, exchanges.size());
        for (int i = 0; i < 2; i++) {
            SpanData exchange = exchanges.get(i);
            assertEquals("POST", exchange.getName());
            assertEquals(call.getSpanId(), exchange.getParentSpanId());
            // The PDP got this exchange's own span as its parent
            assertTrue(traceparents.get(i).contains(exchange.getSpanId()), traceparents::toString);
            assertTrue(traceparents.get(i).contains(call.getTraceId()));
            assertEquals(requestIds.get(i), exchange.getAttributes().get(AttributeKey.stringKey("authzen.request_id")));
            assertEquals("POST", exchange.getAttributes().get(AttributeKey.stringKey("http.request.method")));
        }
        assertEquals(503L, exchanges.get(0).getAttributes().get(AttributeKey.longKey("http.response.status_code")));
        assertEquals(StatusCode.ERROR, exchanges.get(0).getStatus().getStatusCode());
        assertNull(exchanges.get(0).getAttributes().get(AttributeKey.longKey("http.request.resend_count")));
        assertEquals(200L, exchanges.get(1).getAttributes().get(AttributeKey.longKey("http.response.status_code")));
        assertEquals(1L, exchanges.get(1).getAttributes().get(AttributeKey.longKey("http.request.resend_count")));
        assertEquals(StatusCode.UNSET, exchanges.get(1).getStatus().getStatusCode());
    }

    @Test
    void neverRecordsIdentifiersOrPersonalData() throws InterruptedException {
        client(OpenTelemetryTracing.builder(openTelemetry).build()).evaluate(REQUEST);

        for (SpanData span : awaitSpans(3)) {
            span.getAttributes().forEach((key, value) -> {
                assertFalse(String.valueOf(value).contains("alice"), key::toString);
                assertFalse(String.valueOf(value).equals("123"), key::toString);
            });
        }
    }

    @Test
    void leavesOutTheResourceTypeAndActionNameWhenAskedTo() throws InterruptedException {
        client(OpenTelemetryTracing.builder(openTelemetry)
                .resourceTypeAttribute(false)
                .actionNameAttribute(false)
                .build()).evaluate(REQUEST);

        SpanData call = named(awaitSpans(3), "authzen evaluation");
        assertNull(call.getAttributes().get(AttributeKey.stringKey("authzen.resource.type")));
        assertNull(call.getAttributes().get(AttributeKey.stringKey("authzen.action.name")));
    }

    @Test
    void sendsNoTraceContextWithoutAnSdk() {
        assertFalse(client(OpenTelemetryTracing.builder(OpenTelemetry.noop()).build()).evaluate(REQUEST)
                .getDecision());

        assertEquals(List.of("null", "null"), traceparents);
    }

    @Test
    void endsTheExchangesOfACallWhoseEventsWereDropped() {
        OpenTelemetryTracing tracing = OpenTelemetryTracing.builder(openTelemetry).build();
        PepTracer.CallSpan call = tracing.startCall(Operation.EVALUATION);
        tracing.startExchange(call, ExchangeStart.builder()
                .operation(Operation.EVALUATION)
                .method("POST")
                .endpoint(ENDPOINT)
                .attempt(1)
                .requestId("r1")
                .build());

        // The AttemptCompleted never arrives
        tracing.onCallCompleted(CallCompleted.builder(Operation.EVALUATION)
                .request(REQUEST)
                .response(EvaluationResponse.of(true))
                .startTime(Instant.now())
                .duration(Duration.ofMillis(5))
                .callSpan(call)
                .build());

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(2, spans.size());
        assertEquals(443L, named(spans, "POST").getAttributes().get(AttributeKey.longKey("server.port")));
    }

    @Test
    void marksFailuresByTheirTypeOnly() {
        OpenTelemetryTracing tracing = OpenTelemetryTracing.builder(openTelemetry).build();
        PepTracer.CallSpan call = tracing.startCall(Operation.EVALUATION);
        PepTracer.ExchangeSpan exchange = tracing.startExchange(call, ExchangeStart.builder()
                .operation(Operation.EVALUATION)
                .method("POST")
                .endpoint(ENDPOINT)
                .attempt(1)
                .requestId("r1")
                .build());
        Instant start = Instant.now();
        tracing.onAttemptCompleted(AttemptCompleted.builder()
                .operation(Operation.EVALUATION)
                .endpoint(ENDPOINT)
                .method("POST")
                .requestId("r1")
                .attempt(1)
                .failure(new IOException("connection refused by 10.0.0.7"))
                .startTime(start)
                .duration(Duration.ofMillis(2))
                .exchangeSpan(exchange)
                .build());
        tracing.onCallCompleted(CallCompleted.builder(Operation.EVALUATION)
                .request(REQUEST)
                .failure(new PdpCommunicationException("PDP unreachable"))
                .startTime(start)
                .duration(Duration.ofMillis(3))
                .callSpan(call)
                .build());

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData post = named(spans, "POST");
        assertEquals(StatusCode.ERROR, post.getStatus().getStatusCode());
        assertEquals(IOException.class.getName(), post.getAttributes().get(AttributeKey.stringKey("error.type")));
        assertTrue(post.getEvents().isEmpty(), "no exception event with its message");
        SpanData failed = named(spans, "authzen evaluation");
        assertEquals("failed", failed.getAttributes().get(AttributeKey.stringKey("authzen.outcome")));
        assertEquals(PdpCommunicationException.class.getName(),
                failed.getAttributes().get(AttributeKey.stringKey("error.type")));
        assertNotNull(failed.getStatus());
        assertEquals(StatusCode.ERROR, failed.getStatus().getStatusCode());
        assertEquals(start.plusMillis(3).toEpochMilli(), TimeUnit.NANOSECONDS.toMillis(failed.getEndEpochNanos()));
    }
}
