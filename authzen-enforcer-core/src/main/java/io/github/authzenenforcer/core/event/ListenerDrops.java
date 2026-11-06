package io.github.authzenenforcer.core.event;

import io.github.authzenenforcer.core.spi.PepListener;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How many events the listeners of a client did not get because their queues were full, as a
 * snapshot taken by {@link io.github.authzenenforcer.core.client.AuthZenClient#listenerDrops()}.
 *
 * <p>The counts only grow while a client lives, so they suit monotonic counters such as a
 * Micrometer {@code FunctionCounter}. Every listener is reported, also one that never missed an
 * event, in the order in which it was registered.
 *
 * <p>Instances are immutable.
 */
public final class ListenerDrops {

    private static final ListenerDrops NONE = new ListenerDrops(List.of());

    /** The kind of an event, as the listener method that receives it. */
    public enum EventType {
        /** A {@link CallCompleted}. */
        CALL_COMPLETED,
        /** An {@link AttemptCompleted}. */
        ATTEMPT_COMPLETED,
        /** A {@link BreakerTransition}. */
        BREAKER_TRANSITION,
        /** A {@link MetadataRefresh}. */
        METADATA_REFRESH
    }

    private final List<Listener> listeners;

    private ListenerDrops(List<Listener> listeners) {
        this.listeners = listeners;
    }

    /**
     * Returns the snapshot of a client without listeners.
     *
     * @return a snapshot that lists no listener
     */
    public static ListenerDrops none() {
        return NONE;
    }

    /**
     * Creates a snapshot. Listeners of the same class get distinct names: the second one of a class
     * is named {@code <class>-2}, the third {@code <class>-3} and so on.
     *
     * @param listeners the listeners in the order of registration, each with its counts; event types
     *                  that are missing count as zero
     * @return the snapshot
     */
    public static ListenerDrops of(List<Map.Entry<PepListener, Map<EventType, Long>>> listeners) {
        Objects.requireNonNull(listeners, "listeners must not be null");
        if (listeners.isEmpty()) {
            return NONE;
        }
        Map<String, Integer> seen = new HashMap<>();
        List<Listener> all = new ArrayList<>(listeners.size());
        for (Map.Entry<PepListener, Map<EventType, Long>> entry : listeners) {
            PepListener listener = Objects.requireNonNull(entry.getKey(), "listener must not be null");
            String base = listener.getClass().getName();
            int occurrence = seen.merge(base, 1, Integer::sum);
            String name = occurrence == 1 ? base : base + "-" + occurrence;
            all.add(new Listener(listener, name, Objects.requireNonNull(entry.getValue(), "counts must not be null")));
        }
        return new ListenerDrops(List.copyOf(all));
    }

    /**
     * Returns the listeners in the order in which they were registered.
     *
     * @return the listeners, empty if the client has none
     */
    public List<Listener> getListeners() {
        return listeners;
    }

    /**
     * Returns how many events all listeners together did not get.
     *
     * @return the sum of all counts
     */
    public long getTotal() {
        return listeners.stream().mapToLong(Listener::getTotal).sum();
    }

    @Override
    public String toString() {
        return "ListenerDrops" + listeners;
    }

    /** The counts of one listener. */
    public static final class Listener {

        private final PepListener listener;
        private final String name;
        private final Map<EventType, Long> dropped;

        private Listener(PepListener listener, String name, Map<EventType, Long> dropped) {
            this.listener = listener;
            this.name = name;
            Map<EventType, Long> counts = new EnumMap<>(EventType.class);
            for (EventType type : EventType.values()) {
                long count = dropped.getOrDefault(type, 0L);
                if (count < 0) {
                    throw new IllegalArgumentException("count must not be negative: " + type);
                }
                counts.put(type, count);
            }
            this.dropped = counts;
        }

        /**
         * Returns the listener as it was registered.
         *
         * @return the listener
         */
        public PepListener getListener() {
            return listener;
        }

        /**
         * Returns a name for the listener that is unique within the client: its class name, with a
         * suffix for a second listener of the same class.
         *
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns how many events of a type the listener did not get.
         *
         * @param type the event type
         * @return the count
         */
        public long getDropped(EventType type) {
            return dropped.get(Objects.requireNonNull(type, "type must not be null"));
        }

        /**
         * Returns how many events of all types the listener did not get.
         *
         * @return the count
         */
        public long getTotal() {
            return dropped.values().stream().mapToLong(Long::longValue).sum();
        }

        @Override
        public String toString() {
            return name + dropped;
        }
    }
}
