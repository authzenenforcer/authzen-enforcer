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
 * <p>Each listener has a FIFO queue that is drained by at most one task at a time on the executor,
 * so that a listener gets its events in order and one at a time. A queue is bounded both in events
 * and in the approximate bytes they retain, mostly PDP response bodies. Low-priority events are
 * dropped once a queue reaches 80% of either bound, all events once an event would exceed one;
 * drops are counted and logged at most once a minute.
 *
 * <p>A publisher never runs an integrator's executor: with one, the drain task is handed to it
 * from an internal daemon thread, so that an executor that runs tasks inline or blocks can delay
 * neither a caller nor the HTTP client's threads.
 *
 * <p>Instances are thread-safe.
 */
public final class EventDispatcher {

    /** A dispatcher without listeners; publishing to it does nothing. */
    public static final EventDispatcher NONE = new EventDispatcher();

    /** Events a drain task delivers before it makes way for the queues of other listeners. */
    static final int BATCH = 64;

    /** Bytes counted for every event on top of the bodies it retains. */
    public static final long EVENT_OVERHEAD_BYTES = 512;

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

    private EventDispatcher(List<PepListener> listeners, Executor target, int capacity, long maxBytes,
            EventContent content, LongSupplier clock) {
        this.queues = listeners.stream()
                .map(listener -> new ListenerQueue(listener, target, capacity, maxBytes))
                .toList();
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
     * @param maxBytes  how many bytes the events in each listener's queue may retain, approximately
     * @param content   which values events mask
     * @return the dispatcher
     */
    public static EventDispatcher create(List<PepListener> listeners, Executor executor, int capacity, long maxBytes,
            EventContent content) {
        return create(listeners, executor, capacity, maxBytes, content, System::nanoTime);
    }

    static EventDispatcher create(List<PepListener> listeners, Executor executor, int capacity, long maxBytes,
            EventContent content, LongSupplier clock) {
        if (listeners.isEmpty()) {
            return NONE;
        }
        return direct(listeners, executor != null ? handedOff(executor) : daemonThread("authzen-enforcer-events"),
                capacity, maxBytes, content, clock);
    }

    // Lets tests in this package run drain tasks by hand, without the hand-off thread
    static EventDispatcher direct(List<PepListener> listeners, Executor target, int capacity, long maxBytes,
            EventContent content, LongSupplier clock) {
        if (listeners.isEmpty()) {
            return NONE;
        }
        return new EventDispatcher(List.copyOf(listeners), target, capacity, maxBytes, content, clock);
    }

    private static Executor daemonThread(String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, name);
                    thread.setDaemon(true);
                    return thread;
                });
        // The client has no close method, so the thread must not outlive its work
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    // An integrator's executor may run the task inline or block in execute(), so it is only ever
    // called from this thread. Its queue holds at most one task per listener
    private static Executor handedOff(Executor executor) {
        Executor handOff = daemonThread("authzen-enforcer-events-handoff");
        return task -> handOff.execute(() -> {
            try {
                executor.execute(task);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The listener executor rejected a delivery; the events wait for the next one",
                        e);
                if (task instanceof ListenerQueue queue) {
                    queue.rejected();
                }
            }
        });
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
     * @param bytes the size of the bodies the event retains, such as the PDP response body
     */
    public void publish(CallCompleted<?, ?> event, long bytes) {
        publish(listener -> listener.onCallCompleted(event), false, bytes);
    }

    /**
     * Publishes a completed HTTP exchange, which is dropped first when a queue fills up.
     *
     * @param event the event
     */
    public void publish(AttemptCompleted event) {
        publish(listener -> listener.onAttemptCompleted(event), true, 0);
    }

    /**
     * Publishes a breaker transition.
     *
     * @param event the event
     */
    public void publish(BreakerTransition event) {
        publish(listener -> listener.onBreakerTransition(event), false, 0);
    }

    /**
     * Publishes a metadata refresh; a successful one is dropped first when a queue fills up.
     *
     * @param event the event
     * @param bytes the size of the metadata document the event retains
     */
    public void publish(MetadataRefresh event, long bytes) {
        boolean low = event.getResult() == MetadataRefresh.Result.FRESH
                || event.getResult() == MetadataRefresh.Result.DEFAULT_PATHS;
        publish(listener -> listener.onMetadataRefresh(event), low, bytes);
    }

    private void publish(Consumer<PepListener> delivery, boolean low, long bytes) {
        long weight = EVENT_OVERHEAD_BYTES + Math.max(0, bytes);
        for (ListenerQueue queue : queues) {
            try {
                if (!queue.offer(delivery, low, weight)) {
                    onDropped();
                }
            } catch (RuntimeException e) {
                // A publisher must never fail; this only guards against a broken executor
                LOGGER.log(Level.WARNING, "Delivering an event to a PEP listener failed", e);
            }
        }
    }

    /**
     * Returns how many events were dropped because a listener's queue had no room for them.
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

    /** An event waiting for a listener, with the bytes it retains. */
    private record Entry(Consumer<PepListener> delivery, long weight) {
    }

    /** The queue of one listener, drained by at most one task at a time. */
    static final class ListenerQueue implements Runnable {

        private final PepListener listener;
        private final Executor executor;
        private final int capacity;
        private final int lowCapacity;
        private final long maxBytes;
        private final long lowMaxBytes;
        private final ConcurrentLinkedQueue<Entry> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();
        private final AtomicLong bytes = new AtomicLong();
        private final AtomicBoolean scheduled = new AtomicBoolean();

        ListenerQueue(PepListener listener, Executor executor, int capacity, long maxBytes) {
            this.listener = Objects.requireNonNull(listener, "listener must not be null");
            this.executor = Objects.requireNonNull(executor, "executor must not be null");
            this.capacity = capacity;
            this.lowCapacity = capacity * 4 / 5;
            this.maxBytes = maxBytes;
            this.lowMaxBytes = maxBytes / 5 * 4;
        }

        /** Queues an event, or returns {@code false} if the queue has no room for it. */
        boolean offer(Consumer<PepListener> delivery, boolean low, long weight) {
            if (!reserve(size, 1, low ? lowCapacity : capacity)) {
                return false;
            }
            if (!reserve(bytes, weight, low ? lowMaxBytes : maxBytes)) {
                size.decrementAndGet();
                return false;
            }
            queue.add(new Entry(delivery, weight));
            schedule();
            return true;
        }

        private static boolean reserve(AtomicInteger counter, int amount, int limit) {
            int current;
            do {
                current = counter.get();
                if (current + amount > limit) {
                    return false;
                }
            } while (!counter.compareAndSet(current, current + amount));
            return true;
        }

        private static boolean reserve(AtomicLong counter, long amount, long limit) {
            long current;
            do {
                current = counter.get();
                // amount and limit are far below Long.MAX_VALUE, so the sum cannot overflow
                if (current + amount > limit) {
                    return false;
                }
            } while (!counter.compareAndSet(current, current + amount));
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
                rejected();
                throw e;
            }
        }

        /** Called when the executor did not take the drain task. */
        void rejected() {
            scheduled.set(false);
        }

        @Override
        public void run() {
            try {
                for (int i = 0; i < BATCH; i++) {
                    Entry entry = queue.poll();
                    if (entry == null) {
                        return;
                    }
                    size.decrementAndGet();
                    bytes.addAndGet(-entry.weight());
                    deliver(entry.delivery());
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
