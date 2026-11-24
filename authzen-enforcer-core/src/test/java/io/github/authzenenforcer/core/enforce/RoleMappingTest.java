package io.github.authzenenforcer.core.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.internal.json.JsonWriter;
import io.github.authzenenforcer.core.model.Subject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RoleMappingTest {

    private static final List<String> AUTHORITIES = List.of("ROLE_admin", "SCOPE_read", "ROLE_editor", "ROLE_admin");

    @Test
    void keepsTheNamesWithThePrefixWithoutIt() {
        RoleMapping mapping = RoleMapping.toProperty("roles").prefix("ROLE_").build();

        assertEquals(List.of("admin", "editor"), mapping.roles(AUTHORITIES));
    }

    @Test
    void keepsEveryNameWithoutAPrefix() {
        RoleMapping mapping = RoleMapping.toProperty("roles").build();

        assertEquals(List.of("ROLE_admin", "SCOPE_read", "ROLE_editor"), mapping.roles(AUTHORITIES));
        assertTrue(mapping.getPrefix().isEmpty());
    }

    @Test
    void leavesOutTheBarePrefixAndNullNames() {
        RoleMapping mapping = RoleMapping.toProperty("roles").prefix("ROLE_").build();

        assertEquals(List.of("viewer"), mapping.roles(Arrays.asList("ROLE_", null, "ROLE_viewer", "role_admin")));
    }

    @Test
    void addsTheRolesToTheSubjectUnderAFlatName() {
        RoleMapping mapping = RoleMapping.toProperty("cerbos.roles").prefix("ROLE_").build();
        Subject subject = Subject.builder().type("user").id("alice").property("department", "sales").build();

        Subject mapped = mapping.apply(subject, AUTHORITIES);

        assertEquals("user", mapped.getType());
        assertEquals("alice", mapped.getId());
        assertEquals(Map.of("department", "sales", "cerbos.roles", List.of("admin", "editor")), mapped.getProperties());
        // One property with a dot in its name, not a nested object
        assertTrue(new JsonWriter().write(mapped.getProperties()).contains("\"cerbos.roles\":[\"admin\",\"editor\"]"));
    }

    @Test
    void replacesAPropertyOfTheSameName() {
        RoleMapping mapping = RoleMapping.toProperty("roles").prefix("ROLE_").build();
        Subject subject = Subject.builder().type("user").id("alice").property("roles", List.of("from-claims")).build();

        assertEquals(Map.of("roles", List.of("admin", "editor")), mapping.apply(subject, AUTHORITIES).getProperties());
    }

    @Test
    void removesAPropertyOfTheSameNameWithoutRoles() {
        RoleMapping mapping = RoleMapping.toProperty("roles").prefix("ROLE_").build();
        Subject subject = Subject.builder().type("user").id("alice").property("roles", List.of("from-claims"))
                .property("department", "sales").build();

        // The mapping owns the property: other values never pass for the roles
        assertEquals(Map.of("department", "sales"), mapping.apply(subject, List.of("SCOPE_read")).getProperties());
        assertEquals(Map.of("department", "sales"), mapping.apply(subject, List.of()).getProperties());
    }

    @Test
    void leavesASubjectWithoutThePropertyAsItIsWithoutRoles() {
        RoleMapping mapping = RoleMapping.toProperty("roles").prefix("ROLE_").build();
        Subject subject = Subject.builder().type("user").id("alice").property("department", "sales").build();

        assertSame(subject, mapping.apply(subject, List.of("SCOPE_read")));
    }

    @Test
    void rejectsAnEmptyPropertyOrPrefix() {
        assertThrows(IllegalArgumentException.class, () -> RoleMapping.toProperty(""));
        RoleMapping.Builder builder = RoleMapping.toProperty("roles");
        assertThrows(IllegalArgumentException.class, () -> builder.prefix(""));
        assertThrows(NullPointerException.class, () -> RoleMapping.toProperty(null));
    }

    @Test
    void comparesByPropertyAndPrefix() {
        assertEquals(RoleMapping.toProperty("roles").prefix("ROLE_").build(),
                RoleMapping.toProperty("roles").prefix("ROLE_").build());
        assertEquals("RoleMapping{property=roles, prefix=ROLE_}",
                RoleMapping.toProperty("roles").prefix("ROLE_").build().toString());
    }
}
