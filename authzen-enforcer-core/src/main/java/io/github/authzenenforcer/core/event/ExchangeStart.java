package io.github.authzenenforcer.core.event;

import java.net.URI;
import java.util.Objects;

/**
 * An HTTP exchange with the PDP that is about to be sent, as a
 * {@link io.github.authzenenforcer.core.spi.PepTracer} sees it.
 *
 * <p>Instances are immutable and thread-safe. The SDK creates them; the builder lets tests of a
 * tracer create their own.
 */
public final class ExchangeStart {

    private final Operation<?, ?> operation;
    private final String method;
    private final URI endpoint;
    private final int attempt;
    private final String requestId;

    private ExchangeStart(Builder builder) {
        this.operation = Objects.requireNonNull(builder.operation, "operation is required");
        this.method = Objects.requireNonNull(builder.method, "method is required");
        this.endpoint = Objects.requireNonNull(builder.endpoint, "endpoint is required");
        if (builder.attempt < 1) {
            throw new IllegalStateException("attempt must be at least 1");
        }
        this.attempt = builder.attempt;
        this.requestId = Objects.requireNonNull(builder.requestId, "requestId is required");
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
     * @return the operation
     */
    public Operation<?, ?> getOperation() {
        return operation;
    }

    /**
     * Returns the HTTP method.
     *
     * @return the method, such as {@code POST}
     */
    public String getMethod() {
        return method;
    }

    /**
     * Returns the URI the exchange is sent to.
     *
     * @return the endpoint
     */
    public URI getEndpoint() {
        return endpoint;
    }

    /**
     * Returns the position of the exchange within its call, counting retries and the repeat after a
     * {@code 401}.
     *
     * @return 1 for the first exchange
     */
    public int getAttempt() {
        return attempt;
    }

    /**
     * Returns the {@code X-Request-ID} the exchange carries, the same as in its
     * {@link AttemptCompleted}.
     *
     * @return the request id
     */
    public String getRequestId() {
        return requestId;
    }

    @Override
    public String toString() {
        return "ExchangeStart{operation=" + operation + ", method=" + method + ", endpoint=" + endpoint
                + ", attempt=" + attempt + ", requestId=" + requestId + "}";
    }

    /** Builds an {@link ExchangeStart}. */
    public static final class Builder {

        private Operation<?, ?> operation;
        private String method;
        private URI endpoint;
        private int attempt;
        private String requestId;

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
         * Sets the URI.
         *
         * @param endpoint the endpoint
         * @return this builder
         */
        public Builder endpoint(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
            return this;
        }

        /**
         * Sets the position within the call.
         *
         * @param attempt 1 for the first exchange
         * @return this builder
         */
        public Builder attempt(int attempt) {
            this.attempt = attempt;
            return this;
        }

        /**
         * Sets the request id.
         *
         * @param requestId the {@code X-Request-ID}
         * @return this builder
         */
        public Builder requestId(String requestId) {
            this.requestId = Objects.requireNonNull(requestId, "requestId must not be null");
            return this;
        }

        /**
         * Builds the exchange.
         *
         * @return the exchange
         */
        public ExchangeStart build() {
            return new ExchangeStart(this);
        }
    }
}
