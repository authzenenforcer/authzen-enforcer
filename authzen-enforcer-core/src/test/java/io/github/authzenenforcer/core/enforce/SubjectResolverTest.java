package io.github.authzenenforcer.core.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.model.Subject;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SubjectResolverTest {

    private static final Map<String, Object> USER_TOKEN = Map.of(
            "sub", "8f3c0b6e-user",
            "preferred_username", "alice",
            "email", "alice@example.com",
            "exp", Instant.parse("2026-11-10T10:00:00Z"),
            "realm_access", Map.of("roles", List.of("editor", "offline_access")),
            "resource_access", Map.of("pep", Map.of("roles", List.of("auditor"))),
            "groups", List.of("/sales"));

    @Test
    void takesTheIdFromSubAndNothingElseByDefault() {
        Subject subject = SubjectResolver.claims().build().resolve(USER_TOKEN);

        assertEquals(Subject.of("user", "8f3c0b6e-user"), subject);
    }

    @Test
    void appliesTheKeycloakProfile() {
        Subject subject = SubjectResolver.keycloak().build().resolve(USER_TOKEN);

        assertEquals("user", subject.getType());
        assertEquals(Map.of(
                "roles", List.of("editor", "offline_access"),
                "client_roles", Map.of("pep", Map.of("roles", List.of("auditor"))),
                "groups", List.of("/sales")), subject.getProperties());
        assertFalse(subject.getProperties().containsKey("email"), "personal data only when mapped explicitly");
    }

    @Test
    void sendsPersonalDataWhenMappedExplicitly() {
        Subject subject = SubjectResolver.keycloak()
                .property("username", "preferred_username")
                .property("email", "email")
                .build()
                .resolve(USER_TOKEN);

        assertEquals("alice", subject.getProperties().get("username"));
        assertEquals("alice@example.com", subject.getProperties().get("email"));
    }

    @Test
    void recognizesKeycloakServiceAccounts() {
        SubjectResolver keycloak = SubjectResolver.keycloak().build();

        assertEquals("client", keycloak.resolve(Map.of("sub", "1a2b", "client_id", "billing")).getType());
        assertEquals("client", keycloak.resolve(Map.of("sub", "1a2b", "preferred_username", "service-account-billing"))
                .getType());
        assertEquals("user", SubjectResolver.claims().build().resolve(Map.of("sub", "1a2b", "client_id", "billing"))
                .getType(), "only when a service account type is set");
    }

    @Test
    void leavesMissingClaimsOut() {
        Subject subject = SubjectResolver.keycloak().build().resolve(Map.of("sub", "u", "realm_access", "not an object"));

        assertTrue(subject.getProperties().isEmpty());
    }

    @Test
    void refusesClaimsWithoutASubjectId() {
        SubjectResolver resolver = SubjectResolver.claims().build();

        for (Map<String, Object> claims : List.<Map<String, Object>>of(Map.of(), Map.of("sub", ""), Map.of("sub", 42))) {
            InferenceException e = assertThrows(InferenceException.class, () -> resolver.resolve(claims));
            assertTrue(e.isMisconfiguration());
        }
    }

    @Test
    void refusesAMappedClaimThatIsNotJson() {
        SubjectResolver resolver = SubjectResolver.claims().property("expires", "exp").build();

        InferenceException e = assertThrows(InferenceException.class, () -> resolver.resolve(USER_TOKEN));

        assertTrue(e.getMessage().contains("java.time.Instant"), e.getMessage());
        assertFalse(e.getMessage().contains("2026"), "the value is not named");
    }

    @Test
    void usesTheConfiguredIdClaimAndType() {
        Subject subject = SubjectResolver.claims().idClaim("oid").type("employee").build()
                .resolve(Map.of("oid", "e-7", "sub", "ignored"));

        assertEquals(Subject.of("employee", "e-7"), subject);
    }

    @Test
    void rejectsAnEmptyClaimPath() {
        assertThrows(IllegalArgumentException.class, () -> SubjectResolver.claims().property("roles"));
    }
}
