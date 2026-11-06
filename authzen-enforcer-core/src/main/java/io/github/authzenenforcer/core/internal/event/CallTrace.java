package io.github.authzenenforcer.core.internal.event;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.event.AttemptCompleted;
import io.github.authzenenforcer.core.event.CallCompleted;
import io.github.authzenenforcer.core.event.ExchangeStart;
import io.github.authzenenforcer.core.event.MetadataRefresh;
import io.github.authzenenforcer.core.event.Operation;
import io.github.authzenenforcer.core.spi.PepTracer;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects the HTTP exchanges of one call and publishes them, followed by the call itself, once
 * the caller's result is complete.
 *
 * <p>Instances are thread-safe. The exchanges of a call run one after the other, possibly on
 * different threads.
 */
public final class CallTrace {

    private static final System.Logger LOGGER = System.getLogger(CallTrace.class.getName());

    /** A trace that records nothing, for clients without listeners. */
    public static final CallTrace NONE = new CallTrace(EventDispatcher.NONE, Operation.METADATA);

    private final EventDispatcher dispatcher;
    private final Operation<?, ?> operation;
    private final Instant startTime;
    private final long startNanos;
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger exchanges = new AtomicInteger();
    // The metadata request is shared by the callers that wait for it, so it is never traced
    private final PepTracer.CallSpan callSpan;
    private final List<AttemptCompleted> completed = new CopyOnWriteArrayList<>();
    private volatile URI endpoint;
    // The sizes of what the published event retains; the exchanges of a call run one after the other
    private volatile long requestBytes;
    private volatile long responseBytes;

    private CallTrace(EventDispatcher dispatcher, Operation<?, ?> operation) {
        this.dispatcher = dispatcher;
        this.operation = operation;
        boolean active = dispatcher.isActive();
        this.startTime = active ? Instant.now() : Instant.EPOCH;
        this.startNanos = active ? System.nanoTime() : 0;
        this.callSpan = active && operation != Operation.METADATA ? dispatcher.tracer().startCall(operation) : null;
    }

    /**
     * Starts a trace.
     *
     * @param dispatcher where the events go
     * @param operation  the kind of call
     * @return the trace, or {@link #NONE} if the dispatcher has no listeners
     */
    public static CallTrace start(EventDispatcher dispatcher, Operation<?, ?> operation) {
        return dispatcher.isActive() ? new CallTrace(dispatcher, operation) : NONE;
    }

    /**
     * Tells whether the trace records anything.
     *
     * @return {@code false} for {@link #NONE}
     */
    public boolean isActive() {
        return dispatcher.isActive();
    }

    /**
     * Starts the span of an HTTP exchange of a traced call, right before it is sent.
     *
     * @param method    the HTTP method
     * @param endpoint  the URI
     * @param requestId the {@code X-Request-ID} of the exchange
     * @return the span and the trace context that goes to the PDP, or {@code null} for no trace
     *         headers
     */
    public TracerHook.TracedExchange startExchange(String method, URI endpoint, String requestId) {
        if (callSpan == null) {
            return null;
        }
        ExchangeStart exchange = ExchangeStart.builder()
                .operation(operation)
                .method(method)
                .endpoint(endpoint)
                .attempt(exchanges.incrementAndGet())
                .requestId(requestId)
                .build();
        return dispatcher.tracer().startExchange(callSpan, exchange);
    }

    /**
     * Records the endpoint the call is sent to.
     *
     * @param endpoint the endpoint
     */
    public void endpoint(URI endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Records the size of the request body, which the published event retains as a model object.
     *
     * @param bytes the size, in UTF-16 code units of the JSON text
     */
    public void requestBytes(long bytes) {
        if (isActive()) {
            this.requestBytes = bytes;
        }
    }

    /**
     * Records the size of the response body of the latest exchange, which the published event
     * retains as a model object.
     *
     * @param bytes the size of the body
     */
    public void responseBytes(long bytes) {
        if (isActive()) {
            this.responseBytes = bytes;
        }
    }

    /**
     * Records a completed HTTP exchange.
     *
     * @param attempt the exchange, without its operation and position, which the trace adds
     */
    public void attempt(AttemptCompleted.Builder attempt) {
        if (!isActive()) {
            return;
        }
        // Runs before the caller's result completes, so it must never fail the call
        try {
            completed.add(attempt.operation(operation).attempt(attempts.incrementAndGet()).build());
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Recording an HTTP exchange for the PEP listeners failed", e);
        }
    }

    /**
     * Publishes the exchanges and then the completed call.
     *
     * @param operation the operation, the same as the trace's
     * @param request   the request
     * @param response  the response, or {@code null} if the call failed
     * @param failure   the failure the caller received, or {@code null}
     * @param <Q>       the type of the request
     * @param <R>       the type of the response
     */
    public <Q, R> void completed(Operation<Q, R> operation, Q request, R response, AuthZenException failure) {
        if (!isActive()) {
            return;
        }
        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        try {
            publishAttempts();
            CallCompleted.Builder<Q, R> event = CallCompleted.builder(operation)
                    .request(request)
                    .endpoint(endpoint)
                    .startTime(startTime)
                    .duration(duration)
                    .content(dispatcher.content());
            if (callSpan != null) {
                event.callSpan(callSpan);
            }
            completed.forEach(attempt -> event.requestId(attempt.getRequestId()));
            if (failure != null) {
                event.failure(failure);
            } else {
                event.response(response);
            }
            dispatcher.publish(event.build(), failure != null ? requestBytes : requestBytes + responseBytes);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Publishing a call to the PEP listeners failed", e);
        }
    }

    /**
     * Publishes the exchanges and then the metadata refresh.
     *
     * @param refresh the refresh
     */
    public void refreshed(MetadataRefresh.Builder refresh) {
        if (!isActive()) {
            return;
        }
        try {
            publishAttempts();
            dispatcher.publish(refresh.time(Instant.now()).build(), responseBytes);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Publishing a metadata refresh to the PEP listeners failed", e);
        }
    }

    private void publishAttempts() {
        completed.forEach(dispatcher::publish);
    }
}
