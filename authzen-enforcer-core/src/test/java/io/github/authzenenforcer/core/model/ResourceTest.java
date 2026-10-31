package io.github.authzenenforcer.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ResourceTest {

    @Test
    void createsResourceWithoutProperties() {
        Resource resource = Resource.of("account", "123");

        assertEquals("account", resource.getType());
        assertEquals("123", resource.getId());
        assertTrue(resource.getProperties().isEmpty());
    }

    @Test
    void buildsResourceWithNestedProperties() {
        Resource resource = Resource.builder()
                .type("book")
                .id("123")
                .property("library_record", Map.of("title", "AuthZEN in Action"))
                .build();

        assertEquals(Map.of("library_record", Map.of("title", "AuthZEN in Action")), resource.getProperties());
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThrows(IllegalStateException.class, () -> Resource.builder().id("123").build());
        assertThrows(IllegalStateException.class, () -> Resource.builder().type("account").build());
    }

    @Test
    void acceptsEmptyStringsAsTheSpecificationDoes() {
        Resource resource = Resource.of("", "");

        assertEquals("", resource.getType());
        assertEquals("", resource.getId());
    }

    @Test
    void implementsValueEquality() {
        assertEquals(Resource.of("account", "123"), Resource.of("account", "123"));
        assertEquals(Resource.of("account", "123").hashCode(), Resource.of("account", "123").hashCode());
        assertNotEquals(Resource.of("account", "123"), Resource.of("account", "124"));
    }
}
