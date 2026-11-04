package io.github.authzenenforcer.core.model;

import io.github.authzenenforcer.core.internal.json.JsonValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Which page of the results a Search API request asks for.
 *
 * <p>Serialized as the {@code page} object of the request:
 * <pre>{@code
 * {"token": "a3M9NDU2O3N6PTI=", "limit": 2}
 * }</pre>
 *
 * <p>The first request carries no token. Each following request carries the {@code next_token}
 * of the previous response, and everything else, including the limit and the properties of this
 * object, must stay as it was; {@link SubjectSearchRequest#withPageToken(String)} and its
 * counterparts take care of that.
 *
 * <p>A token is opaque, and the SDK sends it as given. A token that went through a client, for
 * example a browser, is user input: the PDP must bind its tokens to the query they were issued for.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class PageRequest {

    private final String token;
    private final Integer limit;
    private final Map<String, Object> properties;

    private PageRequest(Builder builder) {
        this.token = builder.token;
        this.limit = builder.limit;
        this.properties = JsonValues.copyObject(builder.properties);
    }

    /**
     * Creates a request for the first page with at most {@code limit} results.
     *
     * @param limit the maximum number of results, not negative
     * @return the page request
     * @throws IllegalArgumentException if the limit is negative
     */
    public static PageRequest ofLimit(int limit) {
        return builder().limit(limit).build();
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
     * Returns the token of the page asked for.
     *
     * @return the {@code next_token} of the previous response, or empty for the first page
     */
    public Optional<String> getToken() {
        return Optional.ofNullable(token);
    }

    /**
     * Returns the maximum number of results per page.
     *
     * @return the limit, or empty to leave it to the PDP
     */
    public OptionalInt getLimit() {
        return limit != null ? OptionalInt.of(limit) : OptionalInt.empty();
    }

    /**
     * Returns implementation-specific attributes, such as sorting or filtering.
     *
     * @return an unmodifiable map, empty if there are no properties
     */
    public Map<String, Object> getProperties() {
        return properties;
    }

    /**
     * Returns a copy that asks for the page with the given token and keeps everything else.
     *
     * @param token the {@code next_token} of the previous response
     * @return the page request
     */
    public PageRequest withToken(String token) {
        Builder copy = builder().token(Objects.requireNonNull(token, "token must not be null")).properties(properties);
        copy.limit = limit;
        return copy.build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PageRequest other)) {
            return false;
        }
        return Objects.equals(token, other.token) && Objects.equals(limit, other.limit)
                && properties.equals(other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token, limit, properties);
    }

    /**
     * Returns a description for logging. The token is shown only as present or not, since it may
     * encode query details; property values are masked.
     */
    @Override
    public String toString() {
        return "PageRequest{token=" + (token != null ? Redaction.MASK : null) + ", limit=" + limit
                + ", properties=" + Redaction.maskValues(properties) + "}";
    }

    /**
     * Builder for {@link PageRequest}. Not thread-safe.
     */
    public static final class Builder {

        private String token;
        private Integer limit;
        private final Map<String, Object> properties = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the token of the page asked for.
         *
         * @param token the {@code next_token} of the previous response, or {@code null} for the
         *              first page
         * @return this builder
         */
        public Builder token(String token) {
            this.token = token;
            return this;
        }

        /**
         * Sets the maximum number of results per page.
         *
         * <p>The SDK sends the limit as given and sets no upper bound; the PDP may apply its own.
         * Cap a limit that comes from user input.
         *
         * @param limit the limit, not negative
         * @return this builder
         * @throws IllegalArgumentException if the limit is negative
         */
        public Builder limit(int limit) {
            if (limit < 0) {
                throw new IllegalArgumentException("limit must not be negative");
            }
            this.limit = limit;
            return this;
        }

        /**
         * Adds an implementation-specific attribute, replacing any previous value with the same name.
         *
         * @param name  the property name
         * @param value a JSON-compatible value, may be {@code null}
         * @return this builder
         */
        public Builder property(String name, Object value) {
            properties.put(Objects.requireNonNull(name, "name must not be null"), value);
            return this;
        }

        /**
         * Replaces all implementation-specific attributes.
         *
         * @param properties the properties, with JSON-compatible values
         * @return this builder
         */
        public Builder properties(Map<String, ?> properties) {
            Objects.requireNonNull(properties, "properties must not be null");
            this.properties.clear();
            this.properties.putAll(properties);
            return this;
        }

        /**
         * Builds the page request. Properties are deep-copied.
         *
         * @return the page request
         * @throws IllegalArgumentException if a property value is not JSON-compatible
         */
        public PageRequest build() {
            return new PageRequest(this);
        }
    }
}
