package io.github.authzenenforcer.core.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class PermissionSpecTest {

    @Test
    void takesEachValueFromTheFirstSpecThatHasIt() {
        PermissionSpec endpoint = PermissionSpec.builder().action("cancel").context("reason", "endpoint").build();
        PermissionSpec route = PermissionSpec.builder()
                .action("update").resourceType("order").subjectType("client")
                .context("reason", "route").context("channel", "web")
                .build();

        PermissionSpec combined = endpoint.orElse(route);

        assertEquals(Optional.of("cancel"), combined.getAction());
        assertEquals(Optional.of("order"), combined.getResourceType());
        assertEquals(Optional.empty(), combined.getResourceId());
        assertEquals(Optional.of("client"), combined.getSubjectType());
        assertEquals(Map.of("reason", "endpoint", "channel", "web"), combined.getContext());
    }

    @Test
    void keepsAnEmptySpecEmpty() {
        assertTrue(PermissionSpec.none().isEmpty());
        assertSame(PermissionSpec.none(), PermissionSpec.none().orElse(PermissionSpec.none()));
        PermissionSpec spec = PermissionSpec.builder().resourceId("1").build();
        assertSame(spec, spec.orElse(PermissionSpec.none()));
        assertSame(spec, PermissionSpec.none().orElse(spec));
    }

    @Test
    void masksContextValues() {
        String text = PermissionSpec.builder().action("read").context("ip", "10.0.0.1").build().toString();

        assertTrue(text.contains("ip"));
        assertFalse(text.contains("10.0.0.1"));
    }

    @Test
    void rejectsContextValuesThatAreNotJson() {
        assertThrows(IllegalArgumentException.class, () -> PermissionSpec.builder().context("at", Instant.now()).build());
    }
}
