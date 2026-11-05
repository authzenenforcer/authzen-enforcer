package io.github.authzenenforcer.core.event;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A single HTTP exchange with the PDP, including retries and the repetition after a {@code 401}.
 *
 * <p>An exchange either got a response, whose status this event reports whatever it was, or no
 * response at all, for example because the connection failed or timed out. Whether a response
 * with status {@code 200} was valid is reported by the {@link CallCompleted} of the call.
 *
 * <p>Instances are immutable and thread-safe. The SDK creates them; the builder lets tests of a
 * listener create their own.
 */
public final class AttemptCompleted {

    /** What the client did after the exchange. */
    public enum Next {

        /** Nothing: the exchange was the last of its call. */
        NONE,

        /** Repeated the request after a transient failure, see {@link #getRetryDelay()}. */
        RETRY,

        /** Refreshed the credential after a {@code 401} and repeated the request once. */
        REFRESH_CREDENTIAL
    }

    private final Operation<?, ?> operation;
    private final URI endpoint;
    private final String method;
    private final String requestId;
    private final int attempt;
    private final Integer status;
    private final Throwable failure;
    private final Instant startTime;
    private final Duration duration;
    private final Next next;
    private final Duration retryDelay;

    private AttemptCompleted(Builder builder) {
        this.operation = Objects.requireNonNull(builder.operation, "operation is required");
        this.endpoint = Objects.requireNonNull(builder.endpoint, "endpoint is required");
        this.method = Objects.requireNonNull(builder.method, "method is required");
        this.requestId = Objects.requireNonNull(builder.requestId, "requestId is required");
        if (builder.attempt < 1) {
            throw new IllegalStateException("attempt must be at least 1");
        }
        this.attempt = builder.attempt;
        if ((builder.status == null) == (builder.failure == null)) {
            throw new IllegalStateException("exactly one of status and failure is required");
        }
        this.status = builder.status;
        this.failure = builder.failure;
        this.startTime = Objects.requireNonNull(builder.startTime, "startTime is required");
        this.duration = Objects.requireNonNull(builder.duration, "duration is required");
        if ((builder.next == Next.RETRY) != (builder.retryDelay != null)) {
            throw new IllegalStateException("a retry delay is required for and only for Next.RETRY");
        }
        this.next = builder.next;
        this.retryDelay = builder.retryDelay;
    }

    /**
     * Returns a new builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the kind of call the exchange belongs to.
     *
     * @return the operation; {@link Operation#METADATA} for the metadata request
     */
    public Operation<?, ?> getOperation() {
        return operation;
    }

    /**
     * Returns the URL of the request.
     *
     * @return the endpoint
     */
    public URI getEndpoint() {
        return endpoint;
    }

    /**
     * Returns the HTTP method.
     *
     * @return {@code POST}, or {@code GET} for the metadata
     */
    public String getMethod() {
        return method;
    }

    /**
     * Returns the {@code X-Request-ID} of the exchange.
     *
     * @return the request id
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Returns the position of the exchange within its call.
     *
     * @return {@code 1} for the first exchange
     */
    public int getAttempt() {
        return attempt;
    }

    /**
     * Returns the HTTP status of the response.
     *
     * @return the status, or empty if no response arrived
     */
    public OptionalInt getStatus() {
        return status != null ? OptionalInt.of(status) : OptionalInt.empty();
    }

    /**
     * Returns why no response arrived.
     *
     * @return the failure, such as an {@link java.io.IOException} or a timeout, or empty if a
     *         response arrived
     */
    public Optional<Throwable> getFailure() {
        return Optional.ofNullable(failure);
    }

    /**
     * Returns when the exchange started, on the wall clock.
     *
     * @return the start time
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Returns how long the exchange took, measured on a monotonic clock.
     *
     * @return the duration
     */
    public Duration getDuration() {
        return duration;
    }

    /**
     * Returns what the client did after the exchange.
     *
     * @return the next step
     */
    public Next getNext() {
        return next;
    }

    /**
     * Returns how long the client waited before the retry.
     *
     * @return the delay, present only for {@link Next#RETRY}
     */
    public Optional<Duration> getRetryDelay() {
        return Optional.ofNullable(retryDelay);
    }

    @Override
    public String toString() {
        return "AttemptCompleted{operation=" + operation + ", requestId=" + requestId + ", attempt=" + attempt
                + (status != null ? ", status=" + status : ", failure=" + failure.getClass().getSimpleName())
                + ", duration=" + duration.toMillis() + "ms, next=" + next
                + (retryDelay != null ? ", retryDelay=" + retryDelay.toMillis() + "ms" : "") + "}";
    }

    /**
     * Builder for {@link AttemptCompleted}. Not thread-safe.
     */
    public static final class Builder {

        private Operation<?, ?> operation;
        private URI endpoint;
        private String method;
        private String requestId;
        private int attempt;
        private Integer status;
        private Throwable failure;
        private Instant startTime;
        private Duration duration;
        private Next next = Next.NONE;
        private Duration retryDelay;

        private Builder() {
        }

        /**
         * Sets the kind of call.
         *
         * @param operation the operation
         * @return this builder
         */
        public Builder operation(Operation<?, ?> operation) {
            this.operation = Objects.requireNonNull(operation, "operation must not be null");
            return this;
        }

        /**
         * Sets the URL of the request.
         *
         * @param endpoint the endpoint
         * @return this builder
         */
        public Builder endpoint(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
            return this;
        }

        /**
         * Sets the HTTP method.
         *
         * @param method the method
         * @return this builder
         */
        public Builder method(String method) {
            this.method = Objects.requireNonNull(method, "method must not be null");
            return this;
        }

        /**
         * Sets the {@code X-Request-ID}.
         *
         * @param requestId the request id
         * @return this builder
         */
        public Builder requestId(String requestId) {
            this.requestId = Objects.requireNonNull(requestId, "requestId must not be null");
            return this;
        }

        /**
         * Sets the position of the exchange within its call.
         *
         * @param attempt {@code 1} for the first exchange
         * @return this builder
         */
        public Builder attempt(int attempt) {
            this.attempt = attempt;
            return this;
        }

        /**
         * Sets the HTTP status of the response.
         *
         * @param status the status
         * @return this builder
         */
        public Builder status(int status) {
            this.status = status;
            return this;
        }

        /**
         * Sets why no response arrived.
         *
         * @param failure the failure
         * @return this builder
         */
        public Builder failure(Throwable failure) {
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
            return this;
        }

        /**
         * Sets when the exchange started.
         *
         * @param startTime the start time
         * @return this builder
         */
        public Builder startTime(Instant startTime) {
            this.startTime = Objects.requireNonNull(startTime, "startTime must not be null");
            return this;
        }

        /**
         * Sets how long the exchange took.
         *
         * @param duration the duration
         * @return this builder
         */
        public Builder duration(Duration duration) {
            this.duration = Objects.requireNonNull(duration, "duration must not be null");
            return this;
        }

        /**
         * Sets that the client repeated the request after a transient failure.
         *
         * @param delay how long it waited
         * @return this builder
         */
        public Builder retry(Duration delay) {
            this.next = Next.RETRY;
            this.retryDelay = Objects.requireNonNull(delay, "delay must not be null");
            return this;
        }

        /**
         * Sets that the client refreshed the credential and repeated the request.
         *
         * @return this builder
         */
        public Builder refreshCredential() {
            this.next = Next.REFRESH_CREDENTIAL;
            this.retryDelay = null;
            return this;
        }

        /**
         * Builds the event.
         *
         * @return the event
         * @throws NullPointerException  if a required value is missing
         * @throws IllegalStateException if the attempt is below {@code 1}, or unless exactly one of
         *                               status and failure is set
         */
        public AttemptCompleted build() {
            return new AttemptCompleted(this);
        }
    }
}
