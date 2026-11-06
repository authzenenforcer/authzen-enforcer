package io.github.authzenenforcer.core.internal.event;

import io.github.authzenenforcer.core.event.ExchangeStart;
import io.github.authzenenforcer.core.event.Operation;
import io.github.authzenenforcer.core.event.TraceContext;
import io.github.authzenenforcer.core.spi.PepTracer;

import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Calls the start methods of a {@link PepTracer} on the request path, where a failing tracer must
 * neither fail nor change a call: its failures mean no trace headers and are logged at most once
 * a minute.
 *
 * <p>Instances are thread-safe.
 */
public final class TracerHook {

    /**
     * A started exchange span with the trace context it had when it started, read once, so that
     * the headers cannot depend on a tracer that answers differently later.
     *
     * @param span    the span
     * @param context the trace context to send
     */
    public record TracedExchange(PepTracer.ExchangeSpan span, TraceContext context) {
    }

    /** A hook without a tracer; nothing is traced. */
    public static final TracerHook NONE = new TracerHook(null, System::nanoTime);

    private static final System.Logger LOGGER = System.getLogger(TracerHook.class.getName());

    private final PepTracer tracer;
    private final EventDispatcher.Throttle warnings;

    private TracerHook(PepTracer tracer, LongSupplier clock) {
        this.tracer = tracer;
        this.warnings = new EventDispatcher.Throttle(clock);
    }

    /**
     * Creates a hook.
     *
     * @param tracer the tracer, or {@code null} for {@link #NONE}
     * @return the hook
     */
    public static TracerHook of(PepTracer tracer) {
        return of(tracer, System::nanoTime);
    }

    static TracerHook of(PepTracer tracer, LongSupplier clock) {
        return tracer != null ? new TracerHook(tracer, clock) : NONE;
    }

    /**
     * Starts the span of a call.
     *
     * @param operation the kind of call
     * @return the span, or {@code null} if the call is not traced
     */
    public PepTracer.CallSpan startCall(Operation<?, ?> operation) {
        if (tracer == null) {
            return null;
        }
        return guarded(() -> tracer.startCall(operation), "start the span of a call");
    }

    /**
     * Captures the trace of a search over all pages.
     *
     * @param operation the kind of search
     * @return what starts every page under that trace, or {@code null}
     */
    public PepTracer.CallParent captureParent(Operation<?, ?> operation) {
        if (tracer == null) {
            return null;
        }
        return guarded(() -> tracer.captureParent(operation), "capture the trace of a search");
    }

    /**
     * Starts the span of a page of a search over all pages.
     *
     * @param operation the kind of search
     * @param parent    what {@link #captureParent} returned, or {@code null} to start it like any call
     * @return the span, or {@code null} if the call is not traced
     */
    public PepTracer.CallSpan startCall(Operation<?, ?> operation, PepTracer.CallParent parent) {
        if (parent == null) {
            return startCall(operation);
        }
        // A parent only comes from this hook's captureParent, so the tracer exists; checked anyway,
        // since a failure here would also break the logging in guarded()
        if (tracer == null) {
            return null;
        }
        return guarded(() -> tracer.startCall(operation, parent), "start the span of a call");
    }

    /**
     * Starts the span of an exchange and checks that it has a trace context to send.
     *
     * @param call     the span of the call
     * @param exchange the exchange
     * @return the span and its trace context, or {@code null} if the exchange is sent without
     *         trace headers
     */
    public TracedExchange startExchange(PepTracer.CallSpan call, ExchangeStart exchange) {
        Objects.requireNonNull(call, "call must not be null");
        return guarded(() -> {
            PepTracer.ExchangeSpan span = tracer.startExchange(call, exchange);
            TraceContext context = span != null ? span.getTraceContext() : null;
            return context != null ? new TracedExchange(span, context) : null;
        }, "start the span of an HTTP exchange");
    }

    // Errors that a faulty tracer causes are contained, as for listeners; others, such as running
    // out of memory, are not
    private <T> T guarded(Supplier<T> start, String what) {
        try {
            return start.get();
        } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError e) {
            long failures = warnings.count();
            if (warnings.allow()) {
                LOGGER.log(Level.WARNING, "PEP tracer " + tracer.getClass().getName() + " failed to " + what
                        + " (" + failures + " failures so far); the request is sent without trace headers", e);
            }
            return null;
        }
    }
}
