package io.github.authzenenforcer.core.spi;

import io.github.authzenenforcer.core.event.AttemptCompleted;
import io.github.authzenenforcer.core.event.BreakerTransition;
import io.github.authzenenforcer.core.event.CallCompleted;
import io.github.authzenenforcer.core.event.MetadataRefresh;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;

/**
 * Receives events about the client's work with the PDP, for metrics, tracing and audit trails.
 *
 * <p>Register listeners with
 * {@link io.github.authzenenforcer.core.client.AuthZenClient.Builder#listener(PepListener)}. A
 * listener can neither change nor delay a decision:
 * <ul>
 *   <li>Events are delivered after the caller's result is complete, on the listener executor,
 *       never on the calling thread.</li>
 *   <li>Each listener gets its events one at a time and in order: the {@link AttemptCompleted}
 *       events of a call come before its {@link CallCompleted}.</li>
 *   <li>Each listener has its own queue, bounded both in events and in the bytes they retain
 *       (mostly PDP response bodies), so a slow listener cannot make the client hold unbounded
 *       memory. When it fills up, events are dropped rather than held: from 80% of either bound
 *       on, {@link AttemptCompleted} and successful {@link MetadataRefresh} events; when an event
 *       would exceed a bound, that event. A listener that needs every event, such as an audit
 *       trail, should hand them off quickly, for example to its own durable queue.</li>
 *   <li>An exception from a listener is logged and does not stop the delivery of later events.</li>
 * </ul>
 *
 * <p>All methods do nothing by default, so a listener implements the ones it needs.
 *
 * <p>Implementations must be thread-safe if they are registered with more than one client.
 */
public interface PepListener {

    /**
     * Called when an evaluation, a batch evaluation or a search page has completed.
     *
     * @param event the event; use {@link CallCompleted#as} to read its request and response
     */
    default void onCallCompleted(CallCompleted<?, ?> event) {
    }

    /**
     * Called for every HTTP exchange with the PDP, including retries.
     *
     * @param event the event
     */
    default void onAttemptCompleted(AttemptCompleted event) {
    }

    /**
     * Called when a circuit breaker opens or closes.
     *
     * @param event the event
     */
    default void onBreakerTransition(BreakerTransition event) {
    }

    /**
     * Called when a request for the PDP metadata has completed.
     *
     * @param event the event
     */
    default void onMetadataRefresh(MetadataRefresh event) {
    }

    /**
     * Combines listeners into one that calls each of them in order. An exception from one is
     * logged, and the others are still called.
     *
     * @param listeners the listeners
     * @return the combined listener
     */
    static PepListener composite(PepListener... listeners) {
        List<PepListener> all = List.of(Objects.requireNonNull(listeners, "listeners must not be null"));
        return new PepListener() {

            @Override
            public void onCallCompleted(CallCompleted<?, ?> event) {
                all.forEach(listener -> call(listener, () -> listener.onCallCompleted(event)));
            }

            @Override
            public void onAttemptCompleted(AttemptCompleted event) {
                all.forEach(listener -> call(listener, () -> listener.onAttemptCompleted(event)));
            }

            @Override
            public void onBreakerTransition(BreakerTransition event) {
                all.forEach(listener -> call(listener, () -> listener.onBreakerTransition(event)));
            }

            @Override
            public void onMetadataRefresh(MetadataRefresh event) {
                all.forEach(listener -> call(listener, () -> listener.onMetadataRefresh(event)));
            }

            private void call(PepListener listener, Runnable delivery) {
                try {
                    delivery.run();
                } catch (RuntimeException e) {
                    System.getLogger(PepListener.class.getName()).log(Level.WARNING,
                            "PEP listener " + listener.getClass().getName() + " failed", e);
                }
            }
        };
    }
}
