package io.github.authzenenforcer.core.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class RouteOverridesTest {

    private final RouteOverrides overrides = RouteOverrides.builder()
            .route("/documents/{id}", PermissionSpec.builder().resourceType("document").action("view").build())
            .route("post", "/documents/{id}", PermissionSpec.builder().action("comment").build())
            .build();

    @Test
    void combinesTheMethodWithEveryMethod() {
        PermissionSpec post = overrides.forRoute("POST", "/documents/{id}");

        assertEquals(Optional.of("comment"), post.getAction());
        assertEquals(Optional.of("document"), post.getResourceType());
        assertEquals(Optional.of("view"), overrides.forRoute("GET", "/documents/{id}").getAction());
    }

    @Test
    void matchesRoutePatternsExactly() {
        assertEquals(PermissionSpec.none(), overrides.forRoute("GET", "/documents/{docId}"));
        assertEquals(PermissionSpec.none(), RouteOverrides.none().forRoute("GET", "/documents/{id}"));
    }

    @Test
    void rejectsAnEmptyMethod() {
        assertThrows(IllegalArgumentException.class,
                () -> RouteOverrides.builder().route("", "/documents", PermissionSpec.none()));
    }
}
