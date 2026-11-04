package io.github.authzenenforcer.core.internal.client;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.client.PaginationOptions;
import io.github.authzenenforcer.core.model.SearchResponse;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Where a search over all pages stands: the request for the next page and the limits of
 * {@link PaginationOptions}. Each request after the first is the previous one with the PDP's
 * {@code next_token}, so everything else stays as the specification requires (section 8.2).
 *
 * <p>Not thread-safe: {@link #nextRequest()} and {@link #accept(SearchResponse)} take turns, one
 * page at a time.
 *
 * @param <R> the type of the search request
 * @param <T> the type of the results
 */
final class PageCursor<R, T> {

    private final String operation;
    private final BiFunction<R, String, R> withToken;
    private final PaginationOptions options;
    // Bounded by maxPages: one token per page
    private final Set<String> tokens = new HashSet<>();
    private R pending;
    private R current;
    private int pages;
    private int results;

    /**
     * Creates a cursor before the first page.
     *
     * @param operation  the name of the search for exception messages, such as "Subject search"
     * @param first      the request for the first page, which may already carry a token
     * @param firstToken the token of the first request, if any
     * @param withToken  copies a request with another page token
     * @param options    the limits
     */
    PageCursor(String operation, R first, Optional<String> firstToken, BiFunction<R, String, R> withToken,
            PaginationOptions options) {
        this.operation = operation;
        this.pending = Objects.requireNonNull(first, "first must not be null");
        this.withToken = withToken;
        this.options = options;
        firstToken.ifPresent(tokens::add);
    }

    /**
     * Tells whether another page is to be fetched. Between {@link #nextRequest()} and
     * {@link #accept(SearchResponse)} it is {@code false}.
     *
     * @return {@code true} if the last response handed out a token
     */
    boolean hasMorePages() {
        return pending != null;
    }

    /**
     * Returns the request for the next page.
     *
     * @return the request
     * @throws AuthZenException if the search would take more than {@code maxPages} pages
     */
    R nextRequest() {
        if (pages == options.getMaxPages()) {
            throw new AuthZenException(operation + " takes more than " + options.getMaxPages()
                    + " pages (PaginationOptions.maxPages)");
        }
        pages++;
        current = pending;
        pending = null;
        return current;
    }

    /**
     * Takes the response to the last request.
     *
     * @param response the response
     * @return the results of the page
     * @throws PdpProtocolException if the PDP hands out a page token a second time, which would
     *                              make the search go round in circles
     */
    List<T> accept(SearchResponse<T> response) {
        Optional<String> token = response.getNextPageToken();
        if (token.isPresent()) {
            if (!tokens.add(token.get())) {
                throw new PdpProtocolException(operation + " response repeats an earlier page token");
            }
            pending = withToken.apply(current, token.get());
        }
        return response.getResults();
    }

    /**
     * Counts a result that is about to be handed out.
     *
     * @throws AuthZenException if the search would return more than {@code maxResults} results
     */
    void countResult() {
        if (results == options.getMaxResults()) {
            throw new AuthZenException(operation + " returns more than " + options.getMaxResults()
                    + " results (PaginationOptions.maxResults)");
        }
        results++;
    }

    /**
     * Returns a sequential stream of the results that fetches a page only once the results before
     * it are consumed.
     *
     * @param <R>    the type of the search request
     * @param <T>    the type of the results
     * @param cursor a cursor before the first page
     * @param fetch  sends a request and waits for its page
     * @return the stream
     */
    static <R, T> Stream<T> stream(PageCursor<R, T> cursor, Function<R, SearchResponse<T>> fetch) {
        Spliterator<T> results = new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE,
                Spliterator.ORDERED | Spliterator.NONNULL) {

            private Iterator<T> page = Collections.emptyIterator();

            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                while (!page.hasNext()) {
                    if (!cursor.hasMorePages()) {
                        return false;
                    }
                    page = cursor.accept(fetch.apply(cursor.nextRequest())).iterator();
                }
                cursor.countResult();
                action.accept(page.next());
                return true;
            }
        };
        return StreamSupport.stream(results, false);
    }
}
