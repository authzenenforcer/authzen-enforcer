package io.github.authzenenforcer.spring.internal;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.TracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import reactor.util.context.Context;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReactorObservationsTest {

    private final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            // OpenTelemetry 1.68 and later create invalid spans without a span processor
            .setTracerProvider(SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create())).build())
            .build();
    private final ObservationRegistry registry = ObservationRegistry.create();
    private final AtomicInteger calls = new AtomicInteger();

    ReactorObservationsTest() {
        OtelTracer tracer = new OtelTracer(openTelemetry.getTracer("test"), new OtelCurrentTraceContext(), event -> { });
        registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));
    }

    // Every test leaves the test thread as it found it, whatever the order of the tests
    @AfterEach
    void leavesTheTestThreadCleanAndClosesOpenTelemetry() {
        try {
            assertTheThreadIsClean();
        } finally {
            openTelemetry.close();
        }
    }

    @Test
    void runsTheCallInTheScopeOfTheRequestsObservation() {
        Observation observation = Observation.start("request", registry);

        String traceId = ReactorObservations.call(contextOf(observation), currentTraceId());

        TracingObservationHandler.TracingContext tracing = observation.getContext()
                .get(TracingObservationHandler.TracingContext.class);
        assertNotEquals(Span.getInvalid().getSpanContext().getTraceId(), traceId);
        assertEquals(tracing.getSpan().context().traceId(), traceId);
        assertTheThreadIsClean();
        observation.stop();
    }

    @Test
    void runsTheCallAsItIsWithoutAnObservation() {
        String traceId = ReactorObservations.call(Context.empty(), currentTraceId());

        assertEquals(Span.getInvalid().getSpanContext().getTraceId(), traceId);
        assertTheThreadIsClean();
    }

    @Test
    void runsTheCallAndCleansTheThreadWhenAHandlerFailsToOpenTheScope() {
        registry.observationConfig().observationHandler(failing(true, false, new IllegalStateException("open")));
        Observation observation = Observation.start("request", registry);

        ReactorObservations.call(contextOf(observation), currentTraceId());

        assertEquals(1, calls.get());
        // A later task on this worker must not start under this request's trace
        assertTheThreadIsClean();
    }

    @Test
    void keepsTheResultAndCleansTheThreadWhenAHandlerFailsToCloseTheScope() throws Exception {
        registry.observationConfig().observationHandler(failing(false, true, new AssertionError("close")));
        Observation observation = Observation.start("request", registry);

        // From Micrometer 1.18, the failing handler, which runs first on close, keeps the tracing
        // handler from ending its trace context, which stays on the thread. A thread of its own
        // keeps it away from the tests that run after this one
        onAThreadOfItsOwn(() -> {
            String traceId = ReactorObservations.call(contextOf(observation), currentTraceId());

            assertEquals(1, calls.get());
            assertNotEquals(Span.getInvalid().getSpanContext().getTraceId(), traceId);
            // The observation scope is left either way
            assertNull(registry.getCurrentObservationScope());
        });
    }

    @Test
    void forgetsTheScopeWhenAHandlerAlsoFailsToResetIt() {
        ObservationHandler<Observation.Context> failing = failing(true, false, new IllegalStateException("open"));
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onScopeOpened(Observation.Context context) {
                failing.onScopeOpened(context);
            }

            @Override
            public void onScopeReset(Observation.Context context) {
                throw new IllegalStateException("reset");
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        Observation observation = Observation.start("request", registry);

        ReactorObservations.call(contextOf(observation), currentTraceId());

        assertEquals(1, calls.get());
        assertNull(registry.getCurrentObservationScope());
    }

    @Test
    void rethrowsAVirtualMachineErrorWithoutRunningTheCall() {
        registry.observationConfig().observationHandler(failing(true, false, new StackOverflowError()));
        Observation observation = Observation.start("request", registry);

        assertThrows(StackOverflowError.class, () -> ReactorObservations.call(contextOf(observation), currentTraceId()));
        assertEquals(0, calls.get());
        // The failing handler does not fail on close: closing ends the trace context too
        registry.getCurrentObservationScope().close();
        assertTheThreadIsClean();
    }

    private static Context contextOf(Observation observation) {
        return Context.of(ObservationThreadLocalAccessor.KEY, observation);
    }

    private Supplier<String> currentTraceId() {
        return () -> {
            calls.incrementAndGet();
            return Span.current().getSpanContext().getTraceId();
        };
    }

    private void assertTheThreadIsClean() {
        assertNull(registry.getCurrentObservationScope());
        assertFalse(Span.current().getSpanContext().isValid());
    }

    private static void onAThreadOfItsOwn(Runnable test) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                test.run();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        // A scenario that hangs must not keep the test JVM alive
        thread.setDaemon(true);
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(thread.isAlive(), "The test did not end");
        Throwable thrown = failure.get();
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown instanceof Exception exception) {
            throw exception;
        }
        if (thrown != null) {
            throw new AssertionError(thrown);
        }
    }

    private static ObservationHandler<Observation.Context> failing(boolean onOpen, boolean onClose, Throwable error) {
        return new ObservationHandler<>() {
            @Override
            public void onScopeOpened(Observation.Context context) {
                if (onOpen) {
                    throwUnchecked(error);
                }
            }

            @Override
            public void onScopeClosed(Observation.Context context) {
                if (onClose) {
                    throwUnchecked(error);
                }
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        };
    }

    private static void throwUnchecked(Throwable error) {
        if (error instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw (Error) error;
    }
}
