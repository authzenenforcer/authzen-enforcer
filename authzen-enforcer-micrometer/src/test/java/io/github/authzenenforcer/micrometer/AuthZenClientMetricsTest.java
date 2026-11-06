package io.github.authzenenforcer.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.event.ListenerDrops;
import io.github.authzenenforcer.core.event.ListenerDrops.EventType;
import io.github.authzenenforcer.core.spi.PepListener;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class AuthZenClientMetricsTest {

    private static final class Audit implements PepListener {
    }

    private final PepListener audit = new Audit();
    private final PepListener metrics = new PepListener() {
    };
    private final AtomicReference<ListenerDrops> drops = new AtomicReference<>(drops(0, 0));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ListenerDrops drops(long auditCalls, long metricsAttempts) {
        return ListenerDrops.of(List.of(
                Map.entry(audit, Map.of(EventType.CALL_COMPLETED, auditCalls)),
                Map.entry(metrics, Map.of(EventType.ATTEMPT_COMPLETED, metricsAttempts))));
    }

    // Only listenerDrops() is called; any other method would fail the test
    private AuthZenClient client() {
        return (AuthZenClient) Proxy.newProxyInstance(AuthZenClient.class.getClassLoader(),
                new Class<?>[] {AuthZenClient.class}, (proxy, method, args) -> {
                    if (method.getName().equals("listenerDrops")) {
                        return drops.get();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private double dropped(String listener, String event) {
        return registry.get(AuthZenClientMetrics.LISTENER_DROPPED)
                .tags("listener", listener, "event", event)
                .functionCounter()
                .count();
    }

    @Test
    void exposesTheDropsOfEveryListenerAndEventType() {
        AuthZenClientMetrics.bind(client(), registry, Tags.of("pdp", "main"));

        assertEquals(8, registry.get(AuthZenClientMetrics.LISTENER_DROPPED).tag("pdp", "main")
                .functionCounters().size());
        assertEquals(0, dropped(Audit.class.getName(), "call_completed"));

        drops.set(drops(7, 2));

        assertEquals(7, dropped(Audit.class.getName(), "call_completed"));
        assertEquals(0, dropped(Audit.class.getName(), "attempt_completed"));
        assertEquals(2, dropped(metrics.getClass().getName(), "attempt_completed"));
    }

    @Test
    void bindsNothingForAClientWithoutListeners() {
        drops.set(ListenerDrops.none());

        new AuthZenClientMetrics(client(), Tags.empty()).bindTo(registry);

        assertTrue(registry.getMeters().isEmpty());
    }
}
