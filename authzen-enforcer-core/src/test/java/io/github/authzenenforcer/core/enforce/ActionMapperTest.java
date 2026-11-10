package io.github.authzenenforcer.core.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ActionMapperTest {

    @ParameterizedTest
    @CsvSource({
        "GET, read, list", "HEAD, read, list", "POST, create, create", "PUT, update, update",
        "PATCH, update, update", "DELETE, delete, delete", "get, read, list", "Delete, delete, delete"
    })
    void mapsMethodsWithTheDefaultDictionary(String method, String single, String collection) {
        assertEquals(Optional.of(single), ActionMapper.defaults().action(method, false));
        assertEquals(Optional.of(collection), ActionMapper.defaults().action(method, true));
        assertTrue(ActionMapper.defaults().enforces(method));
    }

    @Test
    void leavesCorsPreflightsUnchecked() {
        assertFalse(ActionMapper.defaults().enforces("OPTIONS"));
        assertEquals(Optional.empty(), ActionMapper.defaults().action("OPTIONS", false));
    }

    @Test
    void hasNoActionForOtherMethods() {
        assertEquals(Optional.empty(), ActionMapper.defaults().action("PROPFIND", false));
        assertTrue(ActionMapper.defaults().enforces("PROPFIND"));
    }

    @Test
    void overridesTheDefaults() {
        ActionMapper mapper = ActionMapper.builder()
                .map("GET", "view", "browse")
                .map("PROPFIND", "inspect")
                .map("OPTIONS", "describe")
                .notEnforced("HEAD")
                .build();

        assertEquals(Optional.of("view"), mapper.action("get", false));
        assertEquals(Optional.of("browse"), mapper.action("GET", true));
        assertEquals(Optional.of("inspect"), mapper.action("propfind", true));
        assertTrue(mapper.enforces("OPTIONS"));
        assertFalse(mapper.enforces("HEAD"));
        assertEquals(Optional.empty(), mapper.action("HEAD", false));
        assertEquals(Optional.of("create"), mapper.action("POST", false), "other defaults stay");
    }
}
