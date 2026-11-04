package io.github.authzenenforcer.core.internal.client;

import io.github.authzenenforcer.core.model.SearchResponse;

import java.lang.System.Logger.Level;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Publishes the results of a search over all pages. Each subscriber gets its own search, and a page
 * is fetched only once the subscriber has asked for more results than the earlier pages held.
 *
 * <p>Follows the Reactive Streams rules of {@link Flow}: signals are serialized, a non-positive
 * {@code request} fails the subscription, and nothing is signalled after {@code cancel}, completion
 * or an error. A request that is in flight when the subscriber cancels completes unseen.
 *
 * @param <R> the type of the search request
 * @param <T> the type of the results
 */
final class PagePublisher<R, T> implements Flow.Publisher<T> {

    private static final System.Logger LOGGER = System.getLogger(PagePublisher.class.getName());

    private final String operation;
    private final Supplier<PageCursor<R, T>> cursors;
    private final Function<R, CompletionStage<SearchResponse<T>>> fetch;
    private final Function<Throwable, ? extends Throwable> errors;

    /**
     * Creates a publisher.
     *
     * @param operation the name of the search for log messages
     * @param cursors   creates a cursor before the first page, once per subscriber
     * @param fetch     sends a request for a page
     * @param errors    turns a failure into the error signalled to the subscriber
     */
    PagePublisher(String operation, Supplier<PageCursor<R, T>> cursors,
            Function<R, CompletionStage<SearchResponse<T>>> fetch, Function<Throwable, ? extends Throwable> errors) {
        this.operation = operation;
        this.cursors = cursors;
        this.fetch = fetch;
        this.errors = errors;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber must not be null");
        subscriber.onSubscribe(new PageSubscription(subscriber, cursors.get()));
    }

    private final class PageSubscription implements Flow.Subscription {

        private final Flow.Subscriber<? super T> subscriber;
        private final PageCursor<R, T> cursor;
        private final AtomicLong requested = new AtomicLong();
        // Serializes drain(): only the caller that raises it from 0 runs the loop
        private final AtomicInteger work = new AtomicInteger();
        private volatile boolean cancelled;
        private volatile Throwable failure;
        private volatile SearchResponse<T> arrived;
        // Only used inside drain()
        private Iterator<T> page = Collections.emptyIterator();
        private boolean fetching;

        PageSubscription(Flow.Subscriber<? super T> subscriber, PageCursor<R, T> cursor) {
            this.subscriber = subscriber;
            this.cursor = cursor;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                failure = new IllegalArgumentException("request must be positive (Reactive Streams rule 3.9)");
            } else {
                requested.getAndUpdate(current -> Long.MAX_VALUE - current < n ? Long.MAX_VALUE : current + n);
            }
            drain();
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private void drain() {
            if (work.getAndIncrement() != 0) {
                return;
            }
            int missed = 1;
            do {
                if (!emit()) {
                    // Ended: work stays above 0, so no later drain() signals anything
                    return;
                }
                missed = work.addAndGet(-missed);
            } while (missed != 0);
        }

        // Hands out what it can; returns false once the subscription has ended
        private boolean emit() {
            try {
                while (true) {
                    if (cancelled) {
                        return false;
                    }
                    Throwable error = failure;
                    if (error != null) {
                        return end(() -> subscriber.onError(error));
                    }
                    SearchResponse<T> response = arrived;
                    if (response != null) {
                        arrived = null;
                        fetching = false;
                        page = cursor.accept(response).iterator();
                    }
                    if (fetching) {
                        return true;
                    }
                    // Completing needs no demand (rules 1.4 and 1.5)
                    if (!page.hasNext() && !cursor.hasMorePages()) {
                        return end(subscriber::onComplete);
                    }
                    if (requested.get() == 0) {
                        return true;
                    }
                    if (page.hasNext()) {
                        cursor.countResult();
                        T result = page.next();
                        if (requested.get() != Long.MAX_VALUE) {
                            requested.decrementAndGet();
                        }
                        if (!deliver(result)) {
                            return false;
                        }
                    } else {
                        fetching = true;
                        fetch.apply(cursor.nextRequest()).whenComplete((next, e) -> {
                            if (e != null) {
                                failure = errors.apply(e);
                            } else {
                                arrived = next;
                            }
                            drain();
                        });
                    }
                }
            } catch (RuntimeException e) {
                if (cancelled) {
                    // Thrown by onError or onComplete themselves (rule 2.13)
                    LOGGER.log(Level.WARNING, "Subscriber of a " + operation + " threw on completion", e);
                    return false;
                }
                // A limit, a repeated page token or a request that could not be sent
                Throwable error = errors.apply(e);
                return end(() -> subscriber.onError(error));
            }
        }

        private boolean deliver(T result) {
            try {
                subscriber.onNext(result);
                return true;
            } catch (Throwable e) {
                // Rule 2.13: a subscriber must not throw; treat it as cancelled. Log it first, since
                // on the thread that completed the page nothing else would report it.
                cancelled = true;
                LOGGER.log(Level.WARNING, "Subscriber of a " + operation + " threw; cancelled the subscription", e);
                if (e instanceof Error error) {
                    throw error;
                }
                return false;
            }
        }

        private boolean end(Runnable signal) {
            cancelled = true;
            signal.run();
            return false;
        }
    }
}
