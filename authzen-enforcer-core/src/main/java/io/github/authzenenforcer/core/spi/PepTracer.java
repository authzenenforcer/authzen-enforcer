package io.github.authzenenforcer.core.spi;

import io.github.authzenenforcer.core.event.ExchangeStart;
import io.github.authzenenforcer.core.event.Operation;
import io.github.authzenenforcer.core.event.TraceContext;

/**
 * Traces calls to the PDP and propagates the trace context to it, for distributed tracing such as
 * OpenTelemetry.
 *
 * <p>Tracing has two parts. Propagation decorates each request, like a credential does, so the
 * start methods run synchronously on the request path: {@link #startCall} once per call on the
 * calling thread, and {@link #startExchange} right before every HTTP exchange, including retries,
 * on whatever thread sends it. They must be fast and must not block; starting a span is enough.
 * Ending spans is observation, so a tracer is also a {@link PepListener} and ends its spans from
 * the events, on its own listener thread, where a span exporter may take its time.
 *
 * <p>A tracer can neither fail nor change a call. If {@code startCall} throws or returns
 * {@code null}, the call is not traced and {@code startExchange} is not called for it. If
 * {@code startExchange} throws or returns {@code null}, that exchange is sent without trace
 * headers. Failures are logged, at most once a minute. Only {@code traceparent} and
 * {@code tracestate} are sent, never baggage. The metadata request is not traced: it is shared by
 * the callers that wait for it.
 *
 * <p>A search over all pages ({@code searchAll*}) fetches its pages lazily, on the thread that
 * consumes the results or on an HTTP client thread, where the caller's trace is not current. So
 * {@link #captureParent} runs once, on the thread that calls {@code searchAll*}, and every page
 * of that search, the first one included, is started with {@link #startCall(Operation, CallParent)}
 * under what it captured. If {@code captureParent} throws or returns {@code null}, the pages are
 * started with {@link #startCall(Operation)}. A tracer whose {@code startCall} reads a context of
 * the current thread should therefore implement {@code captureParent} as well; otherwise the pages
 * go under whatever context is current on the thread that fetches them. Every subscription of a
 * publisher from {@code searchAll*Async} shares the parent captured when the publisher was created.
 *
 * <p>The spans are handed back in {@link io.github.authzenenforcer.core.event.CallCompleted#getCallSpan()}
 * and {@link io.github.authzenenforcer.core.event.AttemptCompleted#getExchangeSpan()}, which every
 * listener can read, for example to put the trace id in an audit record. A span whose event was
 * dropped because the tracer could not keep up is not ended by that event; a tracer should end the
 * exchange spans of a call that are still open when the call ends. If the
 * {@link io.github.authzenenforcer.core.event.CallCompleted} of a call is dropped as well, its spans
 * stay open; {@link io.github.authzenenforcer.core.client.AuthZenClient#listenerDrops()} counts
 * such drops.
 *
 * <p>Implementations must be thread-safe.
 *
 * @see io.github.authzenenforcer.core.client.AuthZenClient.Builder#tracer(PepTracer)
 */
public interface PepTracer extends PepListener {

    /**
     * Starts tracing a call, on the calling thread, before its first exchange.
     *
     * @param operation the kind of call
     * @return the span of the call, or {@code null} to leave the call untraced
     */
    CallSpan startCall(Operation<?, ?> operation);

    /**
     * Captures the trace a search over all pages belongs to, on the thread that calls
     * {@code searchAll*}, before any page is fetched. The default captures nothing.
     *
     * @param operation the kind of search
     * @return what {@link #startCall(Operation, CallParent)} needs to start every page under that
     *         trace, or {@code null}
     */
    default CallParent captureParent(Operation<?, ?> operation) {
        return null;
    }

    /**
     * Starts tracing one page of a search over all pages, on whatever thread fetches it, under the
     * trace {@link #captureParent} captured. The default ignores the parent.
     *
     * @param operation the kind of search
     * @param parent    what {@code captureParent} returned for the search
     * @return the span of the call, or {@code null} to leave the call untraced
     */
    default CallSpan startCall(Operation<?, ?> operation, CallParent parent) {
        return startCall(operation);
    }

    /**
     * Starts tracing one HTTP exchange of a traced call, right before it is sent.
     *
     * @param call     the span {@link #startCall} returned for the call
     * @param exchange the exchange
     * @return the span of the exchange, whose trace context is sent to the PDP, or {@code null} to
     *         send none
     */
    ExchangeSpan startExchange(CallSpan call, ExchangeStart exchange);

    /** The trace a search over all pages belongs to; the client only keeps it and hands it back. */
    interface CallParent {
    }

    /** The span of a call, which the client keeps and hands back. */
    interface CallSpan {

        /**
         * Returns the trace context of the call's span.
         *
         * @return the trace context
         */
        TraceContext getTraceContext();
    }

    /** The span of an HTTP exchange, whose trace context the client sends to the PDP. */
    interface ExchangeSpan {

        /**
         * Returns the trace context to send.
         *
         * @return the trace context
         */
        TraceContext getTraceContext();
    }
}
