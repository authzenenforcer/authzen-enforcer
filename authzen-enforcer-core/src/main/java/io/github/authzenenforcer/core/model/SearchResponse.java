package io.github.authzenenforcer.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The answer to a Search API request: the subjects, resources or actions that the PDP permits for
 * the query.
 *
 * <p>Serialized as:
 * <pre>{@code
 * {"page": {"next_token": "", "count": 1}, "results": [{"type": "account", "id": "123"}]}
 * }</pre>
 *
 * <p>A result is not an access decision. The specification expects an Access Evaluation of a
 * result to permit, but does not guarantee it, since the policy may depend on other conditions such
 * as time. Enforce access with an Access Evaluation.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param <T> the type of the results: {@link Subject}, {@link Resource} or {@link Action}
 */
public final class SearchResponse<T> {

    private final List<T> results;
    private final PageResponse page;
    private final Context context;

    private SearchResponse(Builder<T> builder) {
        this.results = List.copyOf(builder.results);
        this.page = builder.page;
        this.context = builder.context;
    }

    /**
     * Returns a new builder.
     *
     * @param <T> the type of the results
     * @return the builder
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Returns the results of this page.
     *
     * <p>The results are kept as the PDP sent them. Like the specification, the model accepts an
     * empty identifier, for example a resource with {@code "id": ""}; reject such a result before
     * using it for further lookups or requests.
     *
     * @return an unmodifiable list in the order the PDP returned them, possibly empty
     */
    public List<T> getResults() {
        return results;
    }

    /**
     * Returns the pagination details.
     *
     * @return the details, or empty if the PDP returned none, which means that the response holds
     *         all results
     */
    public Optional<PageResponse> getPage() {
        return Optional.ofNullable(page);
    }

    /**
     * Returns the token of the next page.
     *
     * @return the token, or empty if this is the last page
     */
    public Optional<String> getNextPageToken() {
        return page != null && page.hasNextPage() ? Optional.of(page.getNextToken()) : Optional.empty();
    }

    /**
     * Returns additional information from the PDP.
     *
     * @return the context, or empty if the PDP returned none
     */
    public Optional<Context> getContext() {
        return Optional.ofNullable(context);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SearchResponse<?> other)) {
            return false;
        }
        return results.equals(other.results) && Objects.equals(page, other.page)
                && Objects.equals(context, other.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(results, page, context);
    }

    @Override
    public String toString() {
        return "SearchResponse{results=" + results + ", page=" + page + ", context=" + context + "}";
    }

    /**
     * Builder for {@link SearchResponse}. Not thread-safe.
     *
     * @param <T> the type of the results
     */
    public static final class Builder<T> {

        private final List<T> results = new ArrayList<>();
        private PageResponse page;
        private Context context;

        private Builder() {
        }

        /**
         * Adds a result.
         *
         * @param result the result
         * @return this builder
         */
        public Builder<T> result(T result) {
            results.add(Objects.requireNonNull(result, "result"));
            return this;
        }

        /**
         * Replaces all results.
         *
         * @param results the results, in order
         * @return this builder
         */
        public Builder<T> results(List<? extends T> results) {
            Objects.requireNonNull(results, "results");
            this.results.clear();
            results.forEach(this::result);
            return this;
        }

        /**
         * Sets the pagination details.
         *
         * @param page the details, or {@code null} for none
         * @return this builder
         */
        public Builder<T> page(PageResponse page) {
            this.page = page;
            return this;
        }

        /**
         * Sets additional information from the PDP.
         *
         * @param context the context, or {@code null} for none
         * @return this builder
         */
        public Builder<T> context(Context context) {
            this.context = context;
            return this;
        }

        /**
         * Builds the response.
         *
         * @return the response
         */
        public SearchResponse<T> build() {
            return new SearchResponse<>(this);
        }
    }
}
