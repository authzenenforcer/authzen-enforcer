package io.github.authzenenforcer.micrometer;

import io.github.authzenenforcer.core.event.ListenerDrops;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Registers a function counter per listener and event type for the drops of a publisher. */
final class ListenerDropsMeters {

    private ListenerDropsMeters() {
    }

    /**
     * Registers the counters. The registry holds the publisher only weakly.
     *
     * @param registry  the registry
     * @param name      the name of the counters
     * @param publisher the client or enforcer whose listeners are counted
     * @param drops     reads the drops of the publisher
     * @param types     the event types the publisher publishes
     * @param tags      tags added to every counter
     * @param <T>       the type of the publisher
     */
    static <T> void register(MeterRegistry registry, String name, T publisher, Function<T, ListenerDrops> drops,
            Set<ListenerDrops.EventType> types, Tags tags) {
        List<ListenerDrops.Listener> listeners = drops.apply(publisher).getListeners();
        for (int i = 0; i < listeners.size(); i++) {
            int index = i;
            for (ListenerDrops.EventType type : types) {
                FunctionCounter.builder(name, publisher, p -> dropped(drops.apply(p), index, type))
                        .description("Events a listener did not get because its queue was full")
                        .tags(tags)
                        .tag("listener", listeners.get(i).getName())
                        .tag("event", MicrometerPepListener.lower(type))
                        .register(registry);
            }
        }
    }

    // A publisher of another implementation may report fewer listeners later; a scrape must not fail
    private static double dropped(ListenerDrops drops, int index, ListenerDrops.EventType type) {
        List<ListenerDrops.Listener> listeners = drops.getListeners();
        return index < listeners.size() ? listeners.get(index).getDropped(type) : 0;
    }
}
