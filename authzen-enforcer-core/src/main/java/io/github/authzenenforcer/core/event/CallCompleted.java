package io.github.authzenenforcer.core.event;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.spi.PepTracer;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A call to the PDP that has completed: an evaluation, a batch evaluation or one page of a search.
 *
 * <p>A call may take several HTTP exchanges with the PDP, each reported as an
 * {@link AttemptCompleted} before this event. A call that failed before any exchange, for example
 * because the circuit breaker was open, has no attempts: it is a deny the PDP never saw.
 *
 * <p>The request and the response are copies in which the values that the client's
 * {@link EventContent} masks are replaced by {@link EventContent#MASK}. Use
 * {@link #as(Operation)} to read them with their types.
 *
 * <p>Instances are immutable and thread-safe. The SDK creates them; the builder lets tests of a
 * listener create their own.
 *
 * @param <Q> the type of the request
 * @param <R> the type of the response
 */
public final class CallCompleted<Q, R> {

    private final Operation<Q, R> operation;
    private final Q request;
    private final R response;
    private final AuthZenException failure;
    private final URI endpoint;
    private final List<String> requestIds;
    private final Instant startTime;
    private final Duration duration;
    private final EventContent content;
    private final PepTracer.CallSpan callSpan;
    // Masked on first use, on the thread of the listener; computing it twice is harmless
    private volatile Q maskedRequest;
    private volatile R maskedResponse;

    private CallCompleted(Builder<Q, R> builder) {
        this.operation = builder.operation;
        this.request = Objects.requireNonNull(builder.request, "request is required");
        if ((builder.response == null) == (builder.failure == null)) {
            throw new IllegalStateException("exactly one of response and failure is required");
        }
        this.response = builder.response;
        this.failure = builder.failure;
        this.endpoint = builder.endpoint;
        this.requestIds = List.copyOf(builder.requestIds);
        this.startTime = Objects.requireNonNull(builder.startTime, "startTime is required");
        this.duration = Objects.requireNonNull(builder.duration, "duration is required");
        this.content = builder.content;
        this.callSpan = builder.callSpan;
    }

    /**
     * Returns a new builder.
     *
     * @param operation the kind of call
     * @param <Q>       the type of the request
     * @param <R>       the type of the response
     * @return the builder
     */
    public static <Q, R> Builder<Q, R> builder(Operation<Q, R> operation) {
        return new Builder<>(Objects.requireNonNull(operation, "operation must not be null"));
    }

    /**
     * Returns this event with the request and response types of the operation, if it is one.
     *
     * @param operation the operation to check for
     * @param <A>       the type of its request
     * @param <B>       the type of its response
     * @return this event, or empty if it is about another operation
     */
    @SuppressWarnings("unchecked")
    public <A, B> Optional<CallCompleted<A, B>> as(Operation<A, B> operation) {
        // The constants are the only instances, so identity implies the type arguments
        return this.operation == operation ? Optional.of((CallCompleted<A, B>) this) : Optional.empty();
    }

    /**
     * Returns the kind of call.
     *
     * @return the operation
     */
    public Operation<Q, R> getOperation() {
        return operation;
    }

    /**
     * Returns how the call ended.
     *
     * @return {@link Outcome#PERMIT} or {@link Outcome#DENY} for an evaluation,
     *         {@link Outcome#COMPLETED} for the other operations, and {@link Outcome#FAILED} if the
     *         call failed
     */
    public Outcome getOutcome() {
        return failure != null ? Outcome.FAILED : operation.outcome(response);
    }

    /**
     * Returns the request, with masked values.
     *
     * @return the request
     */
    public Q getRequest() {
        Q masked = maskedRequest;
        if (masked == null) {
            masked = operation.maskRequest(request, content);
            maskedRequest = masked;
        }
        return masked;
    }

    /**
     * Returns the response, with masked values.
     *
     * @return the response, or empty if the call failed
     */
    public Optional<R> getResponse() {
        if (response == null) {
            return Optional.empty();
        }
        R masked = maskedResponse;
        if (masked == null) {
            masked = operation.maskResponse(response, content);
            maskedResponse = masked;
        }
        return Optional.of(masked);
    }

    /**
     * Returns why the call failed.
     *
     * @return the exception the caller received, or empty if the call succeeded
     */
    public Optional<AuthZenException> getFailure() {
        return Optional.ofNullable(failure);
    }

    /**
     * Returns the endpoint the call was sent to.
     *
     * @return the endpoint, or empty if the call failed before it was known, for example because
     *         discovery failed
     */
    public Optional<URI> getEndpoint() {
        return Optional.ofNullable(endpoint);
    }

    /**
     * Returns the {@code X-Request-ID} of every HTTP exchange of the call, in order.
     *
     * @return the request ids; empty if the call never reached the network
     */
    public List<String> getRequestIds() {
        return requestIds;
    }

    /**
     * Returns the number of HTTP exchanges of the call, including retries and the repetition
     * after a {@code 401}.
     *
     * @return the number of request ids
     */
    public int getAttempts() {
        return requestIds.size();
    }

    /**
     * Returns when the call started, on the wall clock.
     *
     * @return the start time
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Returns how long the call took, measured on a monotonic clock.
     *
     * @return the duration
     */
    public Duration getDuration() {
        return duration;
    }

    /**
     * Returns the span the {@link PepTracer} started for the call, for example to correlate an
     * audit record with the trace through {@code getTraceContext().getTraceId()}.
     *
     * @return the span, or empty if the call was not traced
     */
    public Optional<PepTracer.CallSpan> getCallSpan() {
        return Optional.ofNullable(callSpan);
    }

    @Override
    public String toString() {
        return "CallCompleted{operation=" + operation + ", outcome=" + getOutcome() + ", attempts=" + getAttempts()
                + ", requestIds=" + requestIds + ", duration=" + duration.toMillis() + "ms"
                + (failure != null ? ", failure=" + failure.getClass().getSimpleName() : "") + "}";
    }

    /**
     * Builder for {@link CallCompleted}. Not thread-safe.
     *
     * @param <Q> the type of the request
     * @param <R> the type of the response
     */
    public static final class Builder<Q, R> {

        private final Operation<Q, R> operation;
        private Q request;
        private R response;
        private AuthZenException failure;
        private URI endpoint;
        private final List<String> requestIds = new ArrayList<>();
        private Instant startTime;
        private Duration duration;
        private EventContent content = EventContent.defaults();
        private PepTracer.CallSpan callSpan;

        private Builder(Operation<Q, R> operation) {
            this.operation = operation;
        }

        /**
         * Sets the request, as the caller passed it; the event masks it.
         *
         * @param request the request
         * @return this builder
         */
        public Builder<Q, R> request(Q request) {
            this.request = Objects.requireNonNull(request, "request must not be null");
            return this;
        }

        /**
         * Sets the response of a successful call; the event masks it.
         *
         * @param response the response
         * @return this builder
         */
        public Builder<Q, R> response(R response) {
            this.response = Objects.requireNonNull(response, "response must not be null");
            return this;
        }

        /**
         * Sets the failure of a failed call.
         *
         * @param failure the exception
         * @return this builder
         */
        public Builder<Q, R> failure(AuthZenException failure) {
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
            return this;
        }

        /**
         * Sets the endpoint.
         *
         * @param endpoint the endpoint, or {@code null} if it was not known
         * @return this builder
         */
        public Builder<Q, R> endpoint(URI endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Adds the request id of an HTTP exchange.
         *
         * @param requestId the {@code X-Request-ID}
         * @return this builder
         */
        public Builder<Q, R> requestId(String requestId) {
            requestIds.add(Objects.requireNonNull(requestId, "requestId must not be null"));
            return this;
        }

        /**
         * Sets when the call started.
         *
         * @param startTime the start time
         * @return this builder
         */
        public Builder<Q, R> startTime(Instant startTime) {
            this.startTime = Objects.requireNonNull(startTime, "startTime must not be null");
            return this;
        }

        /**
         * Sets how long the call took.
         *
         * @param duration the duration
         * @return this builder
         */
        public Builder<Q, R> duration(Duration duration) {
            this.duration = Objects.requireNonNull(duration, "duration must not be null");
            return this;
        }

        /**
         * Sets which values the event masks. Defaults to {@link EventContent#defaults()}.
         *
         * @param content the content
         * @return this builder
         */
        public Builder<Q, R> content(EventContent content) {
            this.content = Objects.requireNonNull(content, "content must not be null");
            return this;
        }

        /**
         * Sets the span the tracer started for the call.
         *
         * @param callSpan the span
         * @return this builder
         */
        public Builder<Q, R> callSpan(PepTracer.CallSpan callSpan) {
            this.callSpan = Objects.requireNonNull(callSpan, "callSpan must not be null");
            return this;
        }

        /**
         * Builds the event.
         *
         * @return the event
         * @throws NullPointerException  if the request, start time or duration is missing
         * @throws IllegalStateException unless exactly one of response and failure is set
         */
        public CallCompleted<Q, R> build() {
            return new CallCompleted<>(this);
        }
    }
}
