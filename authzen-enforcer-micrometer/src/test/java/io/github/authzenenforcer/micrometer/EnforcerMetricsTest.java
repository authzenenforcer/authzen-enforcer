package io.github.authzenenforcer.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.core.enforce.PermissionSpec;
import io.github.authzenenforcer.core.enforce.RequestDescription;
import io.github.authzenenforcer.core.event.ListenerDrops.EventType;
import io.github.authzenenforcer.core.spi.PepListener;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

class EnforcerMetricsTest {

    private static final class Audit implements PepListener {
    }

    // A preflight is not enforced, so the enforcer never calls the client
    private static final RequestDescription PREFLIGHT = RequestDescription.builder().method("OPTIONS")
            .routePattern("/documents/{id}").pathVariable("id", "42").build();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static AuthZenClient client() {
        return (AuthZenClient) Proxy.newProxyInstance(AuthZenClient.class.getClassLoader(),
                new Class<?>[] {AuthZenClient.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private double dropped(String listener) {
        return registry.get(EnforcerMetrics.LISTENER_DROPPED)
                .tags("listener", listener, "event", "enforcement_completed")
                .functionCounter()
                .count();
    }

    @Test
    void exposesTheDropsOfEveryListener() {
        // The executor never delivers, so the queue fills up and later events are dropped
        Enforcer enforcer = Enforcer.builder(client())
                .listener(new Audit())
                .listenerExecutor(task -> { })
                .build();
        EnforcerMetrics.bind(enforcer, registry, Tags.of("pdp", "main"));

        assertEquals(1, registry.get(EnforcerMetrics.LISTENER_DROPPED).tag("pdp", "main")
                .functionCounters().size());
        assertEquals(0, dropped(Audit.class.getName()));

        for (int i = 0; i < AuthZenClient.Builder.DEFAULT_LISTENER_QUEUE_CAPACITY + 10; i++) {
            enforcer.enforce(PREFLIGHT, PermissionSpec.none());
        }

        long expected = enforcer.listenerDrops().getListeners().get(0).getDropped(EventType.ENFORCEMENT_COMPLETED);
        assertTrue(expected >= 10, "dropped " + expected);
        assertEquals(expected, dropped(Audit.class.getName()));
    }

    @Test
    void bindsNothingForAnEnforcerWithoutListeners() {
        new EnforcerMetrics(Enforcer.builder(client()).build(), Tags.empty()).bindTo(registry);

        assertTrue(registry.getMeters().isEmpty());
    }
}
