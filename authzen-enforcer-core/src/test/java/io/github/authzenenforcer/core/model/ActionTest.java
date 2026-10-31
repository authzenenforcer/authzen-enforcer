package io.github.authzenenforcer.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ActionTest {

    @Test
    void createsActionWithoutProperties() {
        Action action = Action.of("read");

        assertEquals("read", action.getName());
        assertTrue(action.getProperties().isEmpty());
    }

    @Test
    void buildsActionWithProperties() {
        Action action = Action.builder().name("extend-loan").property("period", "2W").build();

        assertEquals(Map.of("period", "2W"), action.getProperties());
    }

    @Test
    void rejectsMissingName() {
        assertThrows(IllegalStateException.class, () -> Action.builder().build());
    }

    @Test
    void acceptsEmptyNameAsTheSpecificationDoes() {
        assertEquals("", Action.of("").getName());
    }

    @Test
    void implementsValueEquality() {
        assertEquals(Action.of("read"), Action.of("read"));
        assertEquals(Action.of("read").hashCode(), Action.of("read").hashCode());
        assertNotEquals(Action.of("read"), Action.of("delete"));
        assertNotEquals(Action.of("read"), Action.builder().name("read").property("method", "GET").build());
    }
}
