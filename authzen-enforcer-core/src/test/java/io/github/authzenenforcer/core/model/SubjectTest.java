package io.github.authzenenforcer.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SubjectTest {

    @Test
    void createsSubjectWithoutProperties() {
        Subject subject = Subject.of("user", "alice@example.com");

        assertEquals("user", subject.getType());
        assertEquals("alice@example.com", subject.getId());
        assertTrue(subject.getProperties().isEmpty());
    }

    @Test
    void buildsSubjectWithNormalizedProperties() {
        Subject subject = Subject.builder()
                .type("user")
                .id("alice@example.com")
                .property("department", "Sales")
                .property("level", 3)
                .property("roles", List.of("admin"))
                .build();

        assertEquals(Map.of("department", "Sales", "level", 3L, "roles", List.of("admin")),
                subject.getProperties());
    }

    @Test
    void propertiesReplacesPreviousProperties() {
        Subject subject = Subject.builder().type("user").id("alice")
                .property("old", true)
                .properties(Map.of("new", true))
                .build();

        assertEquals(Map.of("new", true), subject.getProperties());
    }

    @Test
    void isNotAffectedByLaterChangesToTheBuilderOrInput() {
        Map<String, Object> properties = new HashMap<>(Map.of("department", "Sales"));
        Subject.Builder builder = Subject.builder().type("user").id("alice").properties(properties);
        Subject subject = builder.build();

        properties.put("department", "Engineering");
        builder.property("extra", 1);

        assertEquals(Map.of("department", "Sales"), subject.getProperties());
        assertThrows(UnsupportedOperationException.class, () -> subject.getProperties().put("x", 1));
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThrows(IllegalStateException.class, () -> Subject.builder().id("alice").build());
        assertThrows(IllegalStateException.class, () -> Subject.builder().type("user").build());
    }

    @Test
    void acceptsEmptyStringsAsTheSpecificationDoes() {
        Subject subject = Subject.of("", "");

        assertEquals("", subject.getType());
        assertEquals("", subject.getId());
    }

    @Test
    void rejectsInvalidProperties() {
        assertThrows(NullPointerException.class, () -> Subject.builder().property(null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> Subject.builder().type("user").id("alice").property("x", new Object()).build());
    }

    @Test
    void implementsValueEquality() {
        Subject a = Subject.builder().type("user").id("alice").property("level", 3).build();
        Subject b = Subject.builder().type("user").id("alice").property("level", 3L).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, Subject.of("user", "alice"));
        assertNotEquals(a, Subject.of("user", "bob"));
        assertNotEquals(Subject.of("user", "alice"), Subject.of("group", "alice"));
    }

    @Test
    void masksIdentifierAndPropertyValuesInToString() {
        Subject subject = Subject.builder().type("user").id("alice@example.com")
                .property("email", "alice@example.com")
                .property("ip_address", "172.217.22.14")
                .build();

        assertEquals("Subject{type=user, id=***, properties={email=***, ip_address=***}}", subject.toString());
    }
}
