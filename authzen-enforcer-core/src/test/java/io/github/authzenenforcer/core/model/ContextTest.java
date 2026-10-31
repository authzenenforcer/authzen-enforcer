package io.github.authzenenforcer.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ContextTest {

    @Test
    void emptyContextHasNoMembers() {
        assertTrue(Context.empty().isEmpty());
        assertTrue(Context.empty().asMap().isEmpty());
        assertSame(Context.empty(), Context.of(Map.of()));
        assertSame(Context.empty(), Context.builder().build());
    }

    @Test
    void buildsContextWithNormalizedValues() {
        Context context = Context.builder()
                .put("time", "1985-10-26T01:22-07:00")
                .put("score", 0.5)
                .build();

        assertFalse(context.isEmpty());
        assertEquals(Map.of("time", "1985-10-26T01:22-07:00", "score", new BigDecimal("0.5")), context.asMap());
    }

    @Test
    void copiesTheInputMap() {
        Map<String, Object> values = new HashMap<>(Map.of("ip", "10.0.0.1"));
        Context context = Context.of(values);
        values.put("ip", "10.0.0.2");

        assertEquals(Map.of("ip", "10.0.0.1"), context.asMap());
        assertThrows(UnsupportedOperationException.class, () -> context.asMap().clear());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(NullPointerException.class, () -> Context.of(null));
        assertThrows(NullPointerException.class, () -> Context.builder().put(null, 1));
        assertThrows(IllegalArgumentException.class, () -> Context.of(Map.of("x", new Object())));
    }

    @Test
    void implementsValueEquality() {
        assertEquals(Context.of(Map.of("a", 1)), Context.builder().put("a", 1L).build());
        assertEquals(Context.of(Map.of("a", 1)).hashCode(), Context.builder().put("a", 1L).build().hashCode());
    }
}
