package io.github.authzenenforcer.opentelemetry;

import io.github.authzenenforcer.core.event.AttemptCompleted;
import io.github.authzenenforcer.core.event.CallCompleted;
import io.github.authzenenforcer.core.event.ExchangeStart;
import io.github.authzenenforcer.core.event.Operation;
import io.github.authzenenforcer.core.event.Outcome;
import io.github.authzenenforcer.core.event.TraceContext;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.spi.PepTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link PepTracer} that records the calls of an
 * {@link io.github.authzenenforcer.core.client.AuthZenClient} as OpenTelemetry spans and sends their
 * W3C trace context to the PDP.
 *
 * <pre>{@code
 * AuthZenClient client = AuthZenClient.builder()
 *         .pdpUrl(URI.create("https://pdp.example.com"))
 *         .tracer(OpenTelemetryTracing.builder(openTelemetry).build())
 *         .build();
 * }</pre>
 *
 * <p>Every call becomes an {@code INTERNAL} span named {@code authzen <operation>}, such as
 * {@code authzen evaluation}, under the span that is current on the calling thread. The pages of a
 * search over all pages ({@code searchAll*}) are fetched later, on other threads, so each of them
 * goes under the span that was current where {@code searchAll*} was called. Every HTTP
 * exchange, retries included, becomes a {@code CLIENT} span named by its method under the call's
 * span, and its context goes to the PDP, so the PDP's server span appears under the exchange that
 * reached it:
 *
 * <pre>
 * authzen evaluation        INTERNAL  authzen.outcome=deny
 * ├─ POST                   CLIENT    http.response.status_code=503
 * │  └─ (PDP server span)
 * └─ POST                   CLIENT    http.response.status_code=200, http.request.resend_count=1
 *    └─ (PDP server span)
 * </pre>
 *
 * <p>Attributes:
 * <ul>
 *   <li>Call span: {@code authzen.operation}, {@code authzen.outcome} ({@code permit}, {@code deny},
 *       {@code completed}, {@code failed} or {@code cancelled}), and for a single evaluation
 *       {@code authzen.resource.type} and {@code authzen.action.name}, which the builder can turn off.
 *       A failed call has the status {@code ERROR} and {@code error.type}, the class name of the
 *       failure; a call its caller cancelled keeps the status unset.</li>
 *   <li>Exchange span: {@code http.request.method}, {@code url.full}, {@code server.address},
 *       {@code server.port}, {@code authzen.request_id} (the {@code X-Request-ID}),
 *       {@code http.request.resend_count} for a repeated exchange, and
 *       {@code http.response.status_code}. A status of 400 or more, or no response, gives the
 *       status {@code ERROR} and {@code error.type}.</li>
 * </ul>
 * No span carries a subject, a resource id, property or context values, or a message of an
 * exception. {@code url.full} and {@code server.address} show the PDP endpoint, path included (for
 * example a tenant or realm segment), but never user info or a query, which the client rejects; do
 * not put secrets in the PDP's path.
 *
 * <p>The trace context is always sent in the W3C format, whatever propagators the application
 * configured, since the PDP is asked for {@code traceparent}. A {@code tracestate} that
 * {@link TraceContext} does not accept, such as a long one inherited from an upstream request, is
 * left out, and only {@code traceparent} is sent. Spans are started on the request
 * path, which takes microseconds, and ended from the events on the listener thread, at the times
 * the events report. An exchange span whose event was dropped is ended with its call. If the
 * call's own event is dropped too, because the tracer's queue was full, the spans of that call are
 * never ended and do not reach the exporter; {@code AuthZenClient.listenerDrops()} counts such
 * drops.
 *
 * <p>Instances are thread-safe; one instance can serve several clients.
 */
public final class OpenTelemetryTracing implements PepTracer {

    /** The instrumentation scope name of the tracer. */
    public static final String INSTRUMENTATION_NAME = "io.github.authzenenforcer";

    private static final W3CTraceContextPropagator W3C = W3CTraceContextPropagator.getInstance();

    private final Tracer tracer;
    private final boolean resourceTypeAttribute;
    private final boolean actionNameAttribute;

    private OpenTelemetryTracing(Builder builder) {
        this.tracer = builder.openTelemetry.getTracer(INSTRUMENTATION_NAME);
        this.resourceTypeAttribute = builder.resourceTypeAttribute;
        this.actionNameAttribute = builder.actionNameAttribute;
    }

    /**
     * Returns a new builder.
     *
     * @param openTelemetry the OpenTelemetry instance whose tracer provider records the spans
     * @return the builder
     */
    public static Builder builder(OpenTelemetry openTelemetry) {
        return new Builder(Objects.requireNonNull(openTelemetry, "openTelemetry must not be null"));
    }

    @Override
    public CallSpan startCall(Operation<?, ?> operation) {
        return start(operation, Context.current());
    }

    @Override
    public CallParent captureParent(Operation<?, ?> operation) {
        return new OtelCallParent(Context.current());
    }

    @Override
    public CallSpan startCall(Operation<?, ?> operation, CallParent parent) {
        return start(operation, parent instanceof OtelCallParent captured ? captured.context : Context.current());
    }

    private CallSpan start(Operation<?, ?> operation, Context parent) {
        Span span = tracer.spanBuilder("authzen " + operation.getName())
                .setParent(parent)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("authzen.operation", operation.getName())
                .startSpan();
        Optional<TraceContext> context = traceContext(span);
        if (context.isEmpty()) {
            // Without an SDK the span is not recorded, and there is nothing to propagate
            span.end();
            return null;
        }
        return new OtelCallSpan(span, context.get());
    }

    @Override
    public ExchangeSpan startExchange(CallSpan call, ExchangeStart exchange) {
        if (!(call instanceof OtelCallSpan parent)) {
            return null;
        }
        URI endpoint = exchange.getEndpoint();
        SpanBuilder builder = tracer.spanBuilder(exchange.getMethod())
                .setParent(Context.root().with(parent.span))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", exchange.getMethod())
                .setAttribute("url.full", endpoint.toString())
                .setAttribute("server.address", endpoint.getHost())
                .setAttribute("server.port", port(endpoint))
                .setAttribute("authzen.request_id", exchange.getRequestId());
        if (exchange.getAttempt() > 1) {
            builder.setAttribute("http.request.resend_count", exchange.getAttempt() - 1L);
        }
        Span span = builder.startSpan();
        Optional<TraceContext> context = traceContext(span);
        if (context.isEmpty()) {
            span.end();
            return null;
        }
        OtelExchangeSpan result = new OtelExchangeSpan(span, context.get());
        parent.exchanges.add(result);
        return result;
    }

    @Override
    public void onAttemptCompleted(AttemptCompleted event) {
        Optional<ExchangeSpan> exchange = event.getExchangeSpan();
        if (exchange.isEmpty() || !(exchange.get() instanceof OtelExchangeSpan traced)) {
            return;
        }
        Span span = traced.span;
        if (event.getStatus().isPresent()) {
            int status = event.getStatus().getAsInt();
            span.setAttribute("http.response.status_code", status);
            if (status >= 400) {
                span.setStatus(StatusCode.ERROR);
                span.setAttribute("error.type", String.valueOf(status));
            }
        } else {
            span.setStatus(StatusCode.ERROR);
            event.getFailure().ifPresent(failure -> span.setAttribute("error.type", failure.getClass().getName()));
        }
        traced.end(event.getStartTime().plus(event.getDuration()));
    }

    @Override
    public void onCallCompleted(CallCompleted<?, ?> event) {
        Optional<CallSpan> call = event.getCallSpan();
        if (call.isEmpty() || !(call.get() instanceof OtelCallSpan traced)) {
            return;
        }
        Span span = traced.span;
        span.setAttribute("authzen.outcome", event.getOutcome().name().toLowerCase(Locale.ROOT));
        if (resourceTypeAttribute || actionNameAttribute) {
            event.as(Operation.EVALUATION).map(CallCompleted::getRequest).ifPresent(request -> describe(span, request));
        }
        if (event.getOutcome() == Outcome.FAILED) {
            span.setStatus(StatusCode.ERROR);
            event.getFailure().ifPresent(failure -> span.setAttribute("error.type", failure.getClass().getName()));
        }
        Instant end = event.getStartTime().plus(event.getDuration());
        // Exchanges whose events were dropped would otherwise never end
        traced.exchanges.forEach(exchange -> exchange.end(end));
        span.end(end);
    }

    private void describe(Span span, EvaluationRequest request) {
        if (resourceTypeAttribute) {
            span.setAttribute("authzen.resource.type", request.getResource().getType());
        }
        if (actionNameAttribute) {
            span.setAttribute("authzen.action.name", request.getAction().getName());
        }
    }

    private static Optional<TraceContext> traceContext(Span span) {
        if (!span.getSpanContext().isValid()) {
            return Optional.empty();
        }
        Map<String, String> headers = new HashMap<>();
        W3C.inject(Context.root().with(span), headers, Map::put);
        String traceparent = headers.get("traceparent");
        if (traceparent == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(TraceContext.of(traceparent, headers.get("tracestate")));
        } catch (IllegalArgumentException e) {
            // A tracestate inherited from upstream may be longer than the client sends; the trace
            // itself still reaches the PDP
            return Optional.of(TraceContext.of(traceparent, null));
        }
    }

    private static long port(URI endpoint) {
        if (endpoint.getPort() != -1) {
            return endpoint.getPort();
        }
        return "http".equalsIgnoreCase(endpoint.getScheme()) ? 80 : 443;
    }

    /** The context that was current where a search over all pages was started. */
    private static final class OtelCallParent implements CallParent {

        final Context context;

        OtelCallParent(Context context) {
            this.context = context;
        }
    }

    /** The span of a call, with the spans of its exchanges. */
    private static final class OtelCallSpan implements CallSpan {

        final Span span;
        final TraceContext context;
        final List<OtelExchangeSpan> exchanges = new CopyOnWriteArrayList<>();

        OtelCallSpan(Span span, TraceContext context) {
            this.span = span;
            this.context = context;
        }

        @Override
        public TraceContext getTraceContext() {
            return context;
        }
    }

    /** The span of an exchange, which is ended once, by its event or by its call. */
    private static final class OtelExchangeSpan implements ExchangeSpan {

        final Span span;
        final TraceContext context;
        private final AtomicBoolean ended = new AtomicBoolean();

        OtelExchangeSpan(Span span, TraceContext context) {
            this.span = span;
            this.context = context;
        }

        void end(Instant time) {
            if (ended.compareAndSet(false, true)) {
                span.end(time);
            }
        }

        @Override
        public TraceContext getTraceContext() {
            return context;
        }
    }

    /** Builds an {@link OpenTelemetryTracing}. */
    public static final class Builder {

        private final OpenTelemetry openTelemetry;
        private boolean resourceTypeAttribute = true;
        private boolean actionNameAttribute = true;

        private Builder(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
        }

        /**
         * Sets whether the span of a single evaluation carries {@code authzen.resource.type}. On by
         * default.
         *
         * @param enabled whether to add the attribute
         * @return this builder
         */
        public Builder resourceTypeAttribute(boolean enabled) {
            this.resourceTypeAttribute = enabled;
            return this;
        }

        /**
         * Sets whether the span of a single evaluation carries {@code authzen.action.name}. On by
         * default.
         *
         * @param enabled whether to add the attribute
         * @return this builder
         */
        public Builder actionNameAttribute(boolean enabled) {
            this.actionNameAttribute = enabled;
            return this;
        }

        /**
         * Builds the tracer.
         *
         * @return the tracer
         */
        public OpenTelemetryTracing build() {
            return new OpenTelemetryTracing(this);
        }
    }
}
