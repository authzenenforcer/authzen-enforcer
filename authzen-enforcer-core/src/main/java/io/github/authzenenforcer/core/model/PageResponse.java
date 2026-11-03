package io.github.authzenenforcer.core.model;

import io.github.authzenenforcer.core.internal.json.JsonValues;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Pagination details of a Search API response.
 *
 * <p>Read from the {@code page} object of the response:
 * <pre>{@code
 * {"next_token": "a3M9NDU2O3N6PTI=", "count": 2, "total": 3}
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class PageResponse {

    private final String nextToken;
    private final Long count;
    private final Long total;
    private final Map<String, Object> properties;

    private PageResponse(Builder builder) {
        this.nextToken = Checks.requireSet(builder.nextToken, "nextToken");
        this.count = builder.count;
        this.total = builder.total;
        this.properties = JsonValues.copyObject(builder.properties);
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
     * Returns the token of the next page.
     *
     * @return the token to send in the next request, or an empty string if this is the last page
     */
    public String getNextToken() {
        return nextToken;
    }

    /**
     * Returns whether more results follow this page.
     *
     * @return {@code true} if the next token is not empty
     */
    public boolean hasNextPage() {
        return !nextToken.isEmpty();
    }

    /**
     * Returns the number of results in this response, as stated by the PDP.
     *
     * @return the count, or empty if the PDP did not state it
     */
    public OptionalLong getCount() {
        return count != null ? OptionalLong.of(count) : OptionalLong.empty();
    }

    /**
     * Returns the number of results matching the query when it was made, as stated by the PDP. If
     * the data changes while paging, the pages may hold a different number.
     *
     * @return the total, or empty if the PDP did not state it
     */
    public OptionalLong getTotal() {
        return total != null ? OptionalLong.of(total) : OptionalLong.empty();
    }

    /**
     * Returns additional pagination attributes, such as an estimated total.
     *
     * @return an unmodifiable map, empty if there are no properties
     */
    public Map<String, Object> getProperties() {
        return properties;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PageResponse other)) {
            return false;
        }
        return nextToken.equals(other.nextToken) && Objects.equals(count, other.count)
                && Objects.equals(total, other.total) && properties.equals(other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nextToken, count, total, properties);
    }

    /**
     * Returns a description for logging. The token is shown only as present or not, since it may
     * encode query details; property values are masked.
     */
    @Override
    public String toString() {
        return "PageResponse{nextToken=" + (hasNextPage() ? Redaction.MASK : "") + ", count=" + count
                + ", total=" + total + ", properties=" + Redaction.maskValues(properties) + "}";
    }

    /**
     * Builder for {@link PageResponse}. Not thread-safe.
     */
    public static final class Builder {

        private String nextToken;
        private Long count;
        private Long total;
        private final Map<String, Object> properties = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the token of the next page. Required.
         *
         * @param nextToken the token, or an empty string if this is the last page
         * @return this builder
         */
        public Builder nextToken(String nextToken) {
            this.nextToken = nextToken;
            return this;
        }

        /**
         * Sets the number of results in the response.
         *
         * @param count the count, not negative
         * @return this builder
         * @throws IllegalArgumentException if the count is negative
         */
        public Builder count(long count) {
            this.count = requireNotNegative(count, "count");
            return this;
        }

        /**
         * Sets the number of results matching the query.
         *
         * @param total the total, not negative
         * @return this builder
         * @throws IllegalArgumentException if the total is negative
         */
        public Builder total(long total) {
            this.total = requireNotNegative(total, "total");
            return this;
        }

        /**
         * Adds a pagination attribute, replacing any previous value with the same name.
         *
         * @param name  the property name
         * @param value a JSON-compatible value, may be {@code null}
         * @return this builder
         */
        public Builder property(String name, Object value) {
            properties.put(Objects.requireNonNull(name, "name"), value);
            return this;
        }

        /**
         * Replaces all pagination attributes.
         *
         * @param properties the properties, with JSON-compatible values
         * @return this builder
         */
        public Builder properties(Map<String, ?> properties) {
            Objects.requireNonNull(properties, "properties");
            this.properties.clear();
            this.properties.putAll(properties);
            return this;
        }

        /**
         * Builds the page details. Properties are deep-copied.
         *
         * @return the page details
         * @throws IllegalStateException    if the next token is not set
         * @throws IllegalArgumentException if a property value is not JSON-compatible
         */
        public PageResponse build() {
            return new PageResponse(this);
        }

        private static long requireNotNegative(long value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }
    }
}
