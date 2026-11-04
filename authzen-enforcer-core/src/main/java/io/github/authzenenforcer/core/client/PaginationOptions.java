package io.github.authzenenforcer.core.client;

import java.util.Objects;

/**
 * Limits for walking all pages of a search, as {@link AuthZenClient#searchAllSubjects(
 * io.github.authzenenforcer.core.model.SubjectSearchRequest)} and its counterparts do.
 *
 * <p>A PDP decides how many pages a search takes. These limits keep a PDP that keeps handing out
 * new page tokens, or a search that matches far more than expected, from making the client send
 * requests and hand out results without end. Reaching a limit fails the search with an
 * {@link io.github.authzenenforcer.core.AuthZenException}; the results are never cut off silently.
 *
 * <pre>{@code
 * AuthZenClient client = AuthZenClient.builder()
 *         .pdpUrl("https://pdp.example.com")
 *         .pagination(PaginationOptions.builder()
 *                 .maxPages(20)
 *                 .maxResults(2_000)
 *                 .build())
 *         .build();
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @see AuthZenClient.Builder#pagination(PaginationOptions)
 */
public final class PaginationOptions {

    /** Default number of pages a search may take. */
    public static final int DEFAULT_MAX_PAGES = 100;

    /** Default number of results a search may return over all its pages. */
    public static final int DEFAULT_MAX_RESULTS = 10_000;

    private static final PaginationOptions DEFAULTS = builder().build();

    private final int maxPages;
    private final int maxResults;

    private PaginationOptions(Builder builder) {
        this.maxPages = builder.maxPages;
        this.maxResults = builder.maxResults;
    }

    /**
     * Returns the default limits.
     *
     * @return the default options
     */
    public static PaginationOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a new builder, initialized with the defaults.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the number of pages a search may take, that is, the number of requests it may send.
     *
     * @return a positive number
     */
    public int getMaxPages() {
        return maxPages;
    }

    /**
     * Returns the number of results a search may return over all its pages.
     *
     * @return a positive number
     */
    public int getMaxResults() {
        return maxResults;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PaginationOptions other)) {
            return false;
        }
        return maxPages == other.maxPages && maxResults == other.maxResults;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxPages, maxResults);
    }

    @Override
    public String toString() {
        return "PaginationOptions{maxPages=" + maxPages + ", maxResults=" + maxResults + "}";
    }

    /**
     * Builder for {@link PaginationOptions}, initialized with the defaults. Not thread-safe.
     */
    public static final class Builder {

        private int maxPages = DEFAULT_MAX_PAGES;
        private int maxResults = DEFAULT_MAX_RESULTS;

        private Builder() {
        }

        /**
         * Sets the number of pages a search may take. Defaults to {@link #DEFAULT_MAX_PAGES}.
         *
         * @param maxPages a positive number
         * @return this builder
         * @throws IllegalArgumentException if the number is not positive
         */
        public Builder maxPages(int maxPages) {
            this.maxPages = requirePositive(maxPages, "maxPages");
            return this;
        }

        /**
         * Sets the number of results a search may return over all its pages. Defaults to
         * {@link #DEFAULT_MAX_RESULTS}.
         *
         * @param maxResults a positive number
         * @return this builder
         * @throws IllegalArgumentException if the number is not positive
         */
        public Builder maxResults(int maxResults) {
            this.maxResults = requirePositive(maxResults, "maxResults");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public PaginationOptions build() {
            return new PaginationOptions(this);
        }

        private static int requirePositive(int value, String name) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
