package io.github.authzenenforcer.core.internal.event;

import io.github.authzenenforcer.core.event.AttemptCompleted;
import io.github.authzenenforcer.core.event.BreakerTransition;
import io.github.authzenenforcer.core.event.CallCompleted;
import io.github.authzenenforcer.core.event.EventContent;
import io.github.authzenenforcer.core.event.MetadataRefresh;
import io.github.authzenenforcer.core.spi.PepListener;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Hands events to the listeners of a client without ever blocking or failing the publisher.
 *
 * <p>Each listener has a bounded FIFO queue that is drained by at most one task at a time on the
 * executor, so that a listener gets its events in order and one at a time. Low-priority events
 * are dropped once a queue is 80% full, all events once it is full; drops are counted and logged
 * at most once a minute.
 *
 * <p>Instances are thread-safe.
 */
public final class EventDispatcher {

    /** A dispatcher without listeners; publishing to it does nothing. */
    public static final EventDispatcher NONE = new EventDispatcher();

    /** Events a drain task delivers before it makes way for the queues of other listeners. */
    static final int BATCH = 64;

    private static final System.Logger LOGGER = System.getLogger(EventDispatcher.class.getName());
    private static final long WARNING_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final List<ListenerQueue> queues;
    private final EventContent content;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastWarning = new AtomicLong();
    private final AtomicBoolean warned = new AtomicBoolean();
    private final LongSupplier clock;

    private EventDispatcher() {
        this.queues = List.of();
        this.content = EventContent.defaults();
        this.clock = System::nanoTime;
    }

    private EventDispatcher(List<PepListener> listeners, Executor executor, int capacity, EventContent content,
            LongSupplier clock) {
        Executor target = executor != null ? executor : defaultExecutor();
        this.queues = listeners.stream().map(listener -> new ListenerQueue(listener, target, capacity)).toList();
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.clock = clock;
    }

    /**
     * Creates a dispatcher.
     *
     * @param listeners the listeners; none gives {@link #NONE}
     * @param executor  the executor that delivers events, or {@code null} for a single daemon thread
     *                  that ends when it has been idle for a while
     * @param capacity  how many events each listener's queue holds
     * @param content   which values events mask
     * @return the dispatcher
     */
    public static EventDispatcher create(List<PepListener> listeners, Executor executor, int capacity,
            EventContent content) {
        return create(listeners, executor, capacity, content, System::nanoTime);
    }

    static EventDispatcher create(List<PepListener> listeners, Executor executor, int capacity,
            EventContent content, LongSupplier clock) {
        if (listeners.isEmpty()) {
            return NONE;
        }
        return new EventDispatcher(List.copyOf(listeners), executor, capacity, content, clock);
    }

    private static Executor defaultExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, "authzen-enforcer-events");
                    thread.setDaemon(true);
                    return thread;
                });
        // The client has no close method, so the thread must not outlive its work
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Tells whether there is any listener, so that callers can skip building events.
     *
     * @return {@code false} for {@link #NONE}
     */
    public boolean isActive() {
        return !queues.isEmpty();
    }

    /**
     * Returns which values events mask.
     *
     * @return the content
     */
    public EventContent content() {
        return content;
    }

    /**
     * Publishes a completed call.
     *
     * @param event the event
     */
    public void publish(CallCompleted<?, ?> event) {
        publish(listener -> listener.onCallCompleted(event), false);
    }

    /**
     * Publishes a completed HTTP exchange, which is dropped first when a queue fills up.
     *
     * @param event the event
     */
    public void publish(AttemptCompleted event) {
        publish(listener -> listener.onAttemptCompleted(event), true);
    }

    /**
     * Publishes a breaker transition.
     *
     * @param event the event
     */
    public void publish(BreakerTransition event) {
        publish(listener -> listener.onBreakerTransition(event), false);
    }

    /**
     * Publishes a metadata refresh; a successful one is dropped first when a queue fills up.
     *
     * @param event the event
     */
    public void publish(MetadataRefresh event) {
        boolean low = event.getResult() == MetadataRefresh.Result.FRESH
                || event.getResult() == MetadataRefresh.Result.DEFAULT_PATHS;
        publish(listener -> listener.onMetadataRefresh(event), low);
    }

    private void publish(Consumer<PepListener> delivery, boolean low) {
        for (ListenerQueue queue : queues) {
            try {
                if (!queue.offer(delivery, low)) {
                    onDropped();
                }
            } catch (RuntimeException e) {
                // A publisher must never fail; this only guards against a broken executor
                LOGGER.log(Level.WARNING, "Delivering an event to a PEP listener failed", e);
            }
        }
    }

    /**
     * Returns how many events were dropped because a listener's queue was full.
     *
     * @return the number of dropped deliveries, counted per listener
     */
    public long dropped() {
        return dropped.get();
    }

    private void onDropped() {
        long count = dropped.incrementAndGet();
        long now = clock.getAsLong();
        boolean warn;
        if (warned.compareAndSet(false, true)) {
            lastWarning.set(now);
            warn = true;
        } else {
            long last = lastWarning.get();
            warn = now - last >= WARNING_INTERVAL_NANOS && lastWarning.compareAndSet(last, now);
        }
        if (warn) {
            LOGGER.log(Level.WARNING, "A PEP listener cannot keep up; " + count
                    + " events dropped so far. Listeners should hand events off quickly");
        }
    }

    /** The queue of one listener, drained by at most one task at a time. */
    static final class ListenerQueue implements Runnable {

        private final PepListener listener;
        private final Executor executor;
        private final int capacity;
        private final int lowCapacity;
        private final ConcurrentLinkedQueue<Consumer<PepListener>> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();
        private final AtomicBoolean scheduled = new AtomicBoolean();

        ListenerQueue(PepListener listener, Executor executor, int capacity) {
            this.listener = Objects.requireNonNull(listener, "listener must not be null");
            this.executor = Objects.requireNonNull(executor, "executor must not be null");
            this.capacity = capacity;
            this.lowCapacity = capacity * 4 / 5;
        }

        /** Queues an event, or returns {@code false} if the queue has no room for it. */
        boolean offer(Consumer<PepListener> delivery, boolean low) {
            int limit = low ? lowCapacity : capacity;
            int current;
            do {
                current = size.get();
                if (current >= limit) {
                    return false;
                }
            } while (!size.compareAndSet(current, current + 1));
            queue.add(delivery);
            schedule();
            return true;
        }

        private void schedule() {
            if (!scheduled.compareAndSet(false, true)) {
                return;
            }
            try {
                executor.execute(this);
            } catch (RuntimeException e) {
                // The queued events stay; the next event tries again
                scheduled.set(false);
                throw e;
            }
        }

        @Override
        public void run() {
            try {
                for (int i = 0; i < BATCH; i++) {
                    Consumer<PepListener> delivery = queue.poll();
                    if (delivery == null) {
                        return;
                    }
                    size.decrementAndGet();
                    deliver(delivery);
                }
            } finally {
                scheduled.set(false);
                // An event queued after the last poll found the task still scheduled
                if (!queue.isEmpty()) {
                    try {
                        schedule();
                    } catch (RuntimeException e) {
                        LOGGER.log(Level.WARNING, "Delivering an event to a PEP listener failed", e);
                    }
                }
            }
        }

        private void deliver(Consumer<PepListener> delivery) {
            try {
                delivery.accept(listener);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "PEP listener " + listener.getClass().getName() + " failed", e);
            }
        }
    }
}
