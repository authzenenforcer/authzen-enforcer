package io.github.authzenenforcer.core.internal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.client.PaginationOptions;
import io.github.authzenenforcer.core.model.PageResponse;
import io.github.authzenenforcer.core.model.SearchResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.Test;

/**
 * The Reactive Streams behaviour of {@link PagePublisher}, with pages the test completes by hand.
 * The requests are plain page numbers.
 */
class PagePublisherTest {

    private final List<Integer> fetched = new ArrayList<>();
    private final List<CompletableFuture<SearchResponse<String>>> pending = new ArrayList<>();
    private final Recorder subscriber = new Recorder();

    private PagePublisher<Integer, String> publisher(PaginationOptions options) {
        return new PagePublisher<>("Test search",
                () -> new PageCursor<>("Test search", 1, Optional.empty(), (page, token) -> Integer.valueOf(token),
                        options),
                page -> {
                    fetched.add(page);
                    CompletableFuture<SearchResponse<String>> future = new CompletableFuture<>();
                    pending.add(future);
                    return future;
                },
                error -> error instanceof CompletionException ? error.getCause() : error);
    }

    private static SearchResponse<String> page(String nextToken, String... results) {
        SearchResponse.Builder<String> builder = SearchResponse.builder();
        for (String result : results) {
            builder.result(result);
        }
        return builder.page(PageResponse.builder().nextToken(nextToken).build()).build();
    }

    private void answer(int index, SearchResponse<String> response) {
        pending.get(index).complete(response);
    }

    private Flow.Subscription subscribe(PaginationOptions options) {
        publisher(options).subscribe(subscriber);
        return subscriber.subscription;
    }

    @Test
    void sendsNothingBeforeTheFirstRequest() {
        subscribe(PaginationOptions.defaults());

        assertTrue(fetched.isEmpty());
        assertTrue(subscriber.items.isEmpty());
    }

    @Test
    void fetchesTheNextPageOnlyForDemandBeyondTheCurrentOne() {
        Flow.Subscription subscription = subscribe(PaginationOptions.defaults());

        subscription.request(1);
        answer(0, page("2", "a", "b"));
        assertEquals(List.of("a"), subscriber.items);

        subscription.request(1);
        assertEquals(List.of("a", "b"), subscriber.items);
        assertEquals(List.of(1), fetched);

        subscription.request(5);
        assertEquals(List.of(1, 2), fetched);
        answer(1, page("", "c"));
        assertEquals(List.of("a", "b", "c"), subscriber.items);
        assertTrue(subscriber.completed);
    }

    @Test
    void keepsOneRequestInFlightWhateverTheDemand() {
        Flow.Subscription subscription = subscribe(PaginationOptions.defaults());

        subscription.request(1);
        subscription.request(Long.MAX_VALUE);
        subscription.request(Long.MAX_VALUE);

        assertEquals(1, fetched.size());
        answer(0, page("2", "a"));
        answer(1, page("", "b"));
        assertEquals(List.of("a", "b"), subscriber.items);
        assertTrue(subscriber.completed);
    }

    @Test
    void completesWithoutFurtherDemandAfterTheLastResult() {
        Flow.Subscription subscription = subscribe(PaginationOptions.defaults());

        subscription.request(2);
        answer(0, page("", "a", "b"));

        assertEquals(List.of("a", "b"), subscriber.items);
        assertTrue(subscriber.completed);
    }

    @Test
    void completesAnEmptySearch() {
        subscribe(PaginationOptions.defaults()).request(1);

        answer(0, page(""));

        assertTrue(subscriber.items.isEmpty());
        assertTrue(subscriber.completed);
    }

    @Test
    void failsANonPositiveRequest() {
        Flow.Subscription subscription = subscribe(PaginationOptions.defaults());

        subscription.request(0);

        assertInstanceOf(IllegalArgumentException.class, subscriber.error);
        subscription.request(1);
        assertTrue(fetched.isEmpty());
    }

    @Test
    void signalsNothingAfterCancel() {
        Flow.Subscription subscription = subscribe(PaginationOptions.defaults());
        subscription.request(10);

        subscription.cancel();
        answer(0, page("2", "a"));

        assertTrue(subscriber.items.isEmpty());
        assertFalse(subscriber.completed);
        assertNull(subscriber.error);
        assertEquals(1, fetched.size());
    }

    @Test
    void signalsAFailedPageOnce() {
        Flow.Subscription subscription = subscribe(PaginationOptions.defaults());
        subscription.request(10);

        pending.get(0).completeExceptionally(new PdpCommunicationException("down"));
        subscription.request(1);

        assertInstanceOf(PdpCommunicationException.class, subscriber.error);
        assertEquals(1, subscriber.signals);
    }

    @Test
    void signalsALimitAfterTheResultsWithinIt() {
        Flow.Subscription subscription = subscribe(PaginationOptions.builder().maxResults(1).build());
        subscription.request(10);

        answer(0, page("", "a", "b"));

        assertEquals(List.of("a"), subscriber.items);
        assertEquals(AuthZenException.class, subscriber.error.getClass());
        assertFalse(subscriber.completed);
    }

    @Test
    void cancelsASubscriberThatThrows() {
        subscriber.throwOnNext = true;
        Flow.Subscription subscription = subscribe(PaginationOptions.defaults());
        subscription.request(10);

        answer(0, page("2", "a", "b"));

        assertEquals(List.of("a"), subscriber.items);
        assertNull(subscriber.error);
        assertEquals(1, fetched.size());
    }

    @Test
    void handlesAPageThatIsAlreadyComplete() {
        PagePublisher<Integer, String> immediate = new PagePublisher<>("Test search",
                () -> new PageCursor<>("Test search", 1, Optional.empty(), (page, token) -> Integer.valueOf(token),
                        PaginationOptions.defaults()),
                page -> CompletableFuture.completedFuture(page < 3 ? page(String.valueOf(page + 1), "r" + page)
                        : page("", "r" + page)),
                error -> error);

        immediate.subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);

        assertEquals(List.of("r1", "r2", "r3"), subscriber.items);
        assertTrue(subscriber.completed);
    }

    @Test
    void rejectsANullSubscriber() {
        PagePublisher<Integer, String> publisher = publisher(PaginationOptions.defaults());

        assertThrows(NullPointerException.class, () -> publisher.subscribe(null));
    }

    private static final class Recorder implements Flow.Subscriber<String> {

        final List<String> items = new ArrayList<>();
        Flow.Subscription subscription;
        Throwable error;
        boolean completed;
        int signals;
        boolean throwOnNext;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(String item) {
            items.add(item);
            if (throwOnNext) {
                throw new IllegalStateException("subscriber failure");
            }
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            signals++;
        }

        @Override
        public void onComplete() {
            completed = true;
            signals++;
        }
    }
}
