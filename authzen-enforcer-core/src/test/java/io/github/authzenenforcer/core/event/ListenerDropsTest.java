package io.github.authzenenforcer.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.authzenenforcer.core.event.ListenerDrops.EventType;
import io.github.authzenenforcer.core.spi.PepListener;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ListenerDropsTest {

    private static final class Audit implements PepListener {
    }

    @Test
    void countsMissingTypesAsZero() {
        ListenerDrops drops = ListenerDrops.of(List.of(Map.entry(new Audit(), Map.of(EventType.CALL_COMPLETED, 3L))));

        ListenerDrops.Listener listener = drops.getListeners().get(0);
        assertEquals(3, listener.getDropped(EventType.CALL_COMPLETED));
        assertEquals(0, listener.getDropped(EventType.ATTEMPT_COMPLETED));
        assertEquals(3, listener.getTotal());
        assertEquals(3, drops.getTotal());
    }

    @Test
    void namesListenersUniquely() {
        PepListener first = new Audit();
        PepListener second = new Audit();
        PepListener other = new PepListener() {
        };

        List<ListenerDrops.Listener> listeners = ListenerDrops.of(List.of(Map.entry(first, Map.of()),
                Map.entry(other, Map.of()), Map.entry(second, Map.of()))).getListeners();

        assertSame(first, listeners.get(0).getListener());
        assertEquals(Audit.class.getName(), listeners.get(0).getName());
        assertEquals(other.getClass().getName(), listeners.get(1).getName());
        assertEquals(Audit.class.getName() + "-2", listeners.get(2).getName());
    }

    @Test
    void rejectsNegativeCounts() {
        List<Map.Entry<PepListener, Map<EventType, Long>>> listeners =
                List.of(Map.entry(new Audit(), Map.of(EventType.CALL_COMPLETED, -1L)));

        assertThrows(IllegalArgumentException.class, () -> ListenerDrops.of(listeners));
    }

    @Test
    void returnsTheSharedEmptySnapshotForNoListeners() {
        assertSame(ListenerDrops.none(), ListenerDrops.of(List.of()));
    }
}
