package io.github.authzenenforcer.core.client;

import static io.github.authzenenforcer.core.client.FakePdp.json;
import static io.github.authzenenforcer.core.client.FakePdp.sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.event.AttemptCompleted;
import io.github.authzenenforcer.core.event.CallCompleted;
import io.github.authzenenforcer.core.event.ExchangeStart;
import io.github.authzenenforcer.core.event.Operation;
import io.github.authzenenforcer.core.event.TraceContext;
import io.github.authzenenforcer.core.internal.client.TestLoggerFinder;
import io.github.authzenenforcer.core.internal.http.PdpTransport;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.spi.PepTracer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthZenClientTracerTest {

    private static final EvaluationRequest REQUEST = EvaluationRequest.of(Subject.of("user", "alice"),
            Action.of("can_read"), Resource.of("account", "123"));
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private FakePdp pdp;
    private final RecordingTracer tracer = new RecordingTracer();

    @BeforeEach
    void startPdp() throws IOException {
        pdp = FakePdp.start();
    }

    @AfterEach
    void stopPdp() {
        pdp.close();
    }

    private AuthZenClient.Builder builder() {
        return AuthZenClient.builder()
                .pdpUrl(pdp.url())
                .discovery(false)
                .requestTimeout(Duration.ofSeconds(2))
                .tracer(tracer);
    }

    private static TraceContext context(int spanId) {
        return TraceContext.of("00-" + TRACE_ID + "-" + String.format("%016x", spanId) + "-01", null);
    }

    private record Span(TraceContext context) implements PepTracer.CallSpan, PepTracer.ExchangeSpan {

        @Override
        public TraceContext getTraceContext() {
            return context;
        }
    }

    /** Hands out spans with distinct ids and records what it is told. */
    private static final class RecordingTracer implements PepTracer {

        final AtomicInteger ids = new AtomicInteger();
        final List<Operation<?, ?>> calls = new CopyOnWriteArrayList<>();
        final List<ExchangeStart> exchanges = new CopyOnWriteArrayList<>();
        final List<Span> exchangeSpans = new CopyOnWriteArrayList<>();
        final List<Object> events = new CopyOnWriteArrayList<>();
        volatile boolean failCall;
        volatile boolean failExchange;
        volatile boolean noExchangeSpan;
        volatile String tracestate;

        @Override
        public CallSpan startCall(Operation<?, ?> operation) {
            if (failCall) {
                throw new IllegalStateException("tracer bug");
            }
            calls.add(operation);
            return new Span(context(ids.incrementAndGet()));
        }

        @Override
        public ExchangeSpan startExchange(CallSpan call, ExchangeStart exchange) {
            exchanges.add(exchange);
            if (failExchange) {
                throw new IllegalStateException("tracer bug");
            }
            if (noExchangeSpan) {
                return null;
            }
            TraceContext base = context(ids.incrementAndGet());
            Span span = new Span(TraceContext.of(base.getTraceparent(), tracestate));
            exchangeSpans.add(span);
            return span;
        }

        @Override
        public void onCallCompleted(CallCompleted<?, ?> event) {
            events.add(event);
        }

        @Override
        public void onAttemptCompleted(AttemptCompleted event) {
            events.add(event);
        }

        List<Object> await(int count) {
            awaitCondition(() -> events.size() >= count);
            return List.copyOf(events);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("condition not met in time");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void sendsTheTraceContextOfEachExchangeAndHandsTheSpansBack() {
        pdp.respond(sequence(json(503, ""), json(200, "{\"decision\": true}")));
        AuthZenClient client = builder().build();

        assertTrue(client.evaluate(REQUEST).getDecision());

        assertEquals(List.of(Operation.EVALUATION), tracer.calls);
        List<FakePdp.RecordedRequest> requests = pdp.requests();
        assertEquals(2, requests.size());
        for (int i = 0; i < 2; i++) {
            ExchangeStart exchange = tracer.exchanges.get(i);
            assertEquals(i + 1, exchange.getAttempt());
            assertEquals("POST", exchange.getMethod());
            assertEquals(Operation.EVALUATION, exchange.getOperation());
            assertEquals(requests.get(i).header(PdpTransport.REQUEST_ID_HEADER), exchange.getRequestId());
            assertEquals(tracer.exchangeSpans.get(i).getTraceContext().getTraceparent(),
                    requests.get(i).header("traceparent"));
            assertNull(requests.get(i).header("tracestate"));
        }

        // The tracer is a listener too and gets its spans back with the events
        List<Object> events = tracer.await(3);
        assertSame(tracer.exchangeSpans.get(0), ((AttemptCompleted) events.get(0)).getExchangeSpan().orElseThrow());
        assertSame(tracer.exchangeSpans.get(1), ((AttemptCompleted) events.get(1)).getExchangeSpan().orElseThrow());
        CallCompleted<?, ?> call = assertInstanceOf(CallCompleted.class, events.get(2));
        assertEquals(TRACE_ID, call.getCallSpan().orElseThrow().getTraceContext().getTraceId());
    }

    @Test
    void sendsTheTracestate() {
        pdp.respond(json(200, "{\"decision\": true}"));
        tracer.tracestate = "vendor=abc";

        builder().build().evaluate(REQUEST);

        assertEquals("vendor=abc", pdp.requests().get(0).header("tracestate"));
    }

    @Test
    void leavesTheCallUntracedWhenStartingItsSpanFails() {
        pdp.respond(json(200, "{\"decision\": true}"));
        tracer.failCall = true;
        TestLoggerFinder.clearRecords();

        assertTrue(builder().build().evaluate(REQUEST).getDecision());

        assertNull(pdp.requests().get(0).header("traceparent"));
        assertTrue(tracer.exchanges.isEmpty());
        CallCompleted<?, ?> call = assertInstanceOf(CallCompleted.class, tracer.await(2).get(1));
        assertTrue(call.getCallSpan().isEmpty());
        assertTrue(TestLoggerFinder.records().stream().anyMatch(r -> r.contains("failed to start the span of a call")),
                TestLoggerFinder.records()::toString);
    }

    @Test
    void sendsNoTraceHeadersWhenStartingAnExchangeSpanFails() {
        pdp.respond(json(200, "{\"decision\": true}"));
        tracer.failExchange = true;

        assertTrue(builder().build().evaluate(REQUEST).getDecision());

        assertNull(pdp.requests().get(0).header("traceparent"));
        AttemptCompleted attempt = assertInstanceOf(AttemptCompleted.class, tracer.await(2).get(0));
        assertTrue(attempt.getExchangeSpan().isEmpty());
    }

    @Test
    void sendsNoTraceHeadersWithoutAnExchangeSpan() {
        pdp.respond(json(200, "{\"decision\": true}"));
        tracer.noExchangeSpan = true;

        assertTrue(builder().build().evaluate(REQUEST).getDecision());

        assertNull(pdp.requests().get(0).header("traceparent"));
        assertEquals(1, tracer.exchanges.size());
    }

    @Test
    void neverTracesTheMetadataRequest() {
        pdp.respond(json(200, "{\"decision\": true}"));
        pdp.respondToMetadata(json(404, ""));
        AuthZenClient client = builder().discovery(true).build();

        assertTrue(client.evaluate(REQUEST).getDecision());

        assertNull(pdp.metadataRequests().get(0).header("traceparent"));
        assertEquals(List.of(Operation.EVALUATION), tracer.calls);
        assertEquals(1, tracer.exchanges.size());
        assertEquals(tracer.exchangeSpans.get(0).getTraceContext().getTraceparent(),
                pdp.requests().get(0).header("traceparent"));
    }

    @Test
    void usesTheLastTracer() {
        pdp.respond(json(200, "{\"decision\": true}"));
        RecordingTracer earlier = new RecordingTracer();

        AuthZenClient.builder().pdpUrl(pdp.url()).discovery(false).tracer(earlier).tracer(tracer).build()
                .evaluate(REQUEST);

        assertTrue(earlier.calls.isEmpty());
        assertEquals(1, tracer.calls.size());
    }
}
