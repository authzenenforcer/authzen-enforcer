package io.github.authzenenforcer.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventContentTest {

    @ParameterizedTest
    @ValueSource(strings = {"subject.id", "subject.properties.email", "resource.properties.owner",
            "action.properties.reason", "context.ip"})
    void masksPersonalDataByDefault(String path) {
        assertTrue(EventContent.defaults().isMasked(path));
    }

    @Test
    void showsTheResourceIdByDefault() {
        assertFalse(EventContent.defaults().isMasked("resource.id"));
    }

    @Test
    void aRuleForANameTakesPrecedenceOverTheWildcard() {
        EventContent content = EventContent.defaults()
                .include("subject.properties.tenant")
                .exclude("subject.properties.*");

        assertFalse(content.isMasked("subject.properties.tenant"));
        assertTrue(content.isMasked("subject.properties.email"));
    }

    @Test
    void includesAndExcludesWholeGroups() {
        EventContent content = EventContent.defaults().include("context.*").exclude("resource.id");

        assertFalse(content.isMasked("context.ip"));
        assertTrue(content.isMasked("resource.id"));
    }

    @Test
    void returnsCopies() {
        EventContent defaults = EventContent.defaults();

        EventContent changed = defaults.include("subject.id");

        assertTrue(defaults.isMasked("subject.id"));
        assertFalse(changed.isMasked("subject.id"));
        assertNotEquals(defaults, changed);
        assertEquals(changed, EventContent.defaults().include("subject.id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "subject", "subject.type", "action.name", "subject.properties.", "context.",
            "token", "resource.ids"})
    void rejectsPathsThatNameNoMaskableValue(String path) {
        assertThrows(IllegalArgumentException.class, () -> EventContent.defaults().include(path));
        assertThrows(IllegalArgumentException.class, () -> EventContent.defaults().exclude(path));
        assertThrows(IllegalArgumentException.class, () -> EventContent.defaults().isMasked(path));
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> EventContent.defaults().include(null));
    }
}
