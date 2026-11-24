package io.github.authzenenforcer.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.enforce.RoleMapping;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Access Evaluation API against Cerbos, with the roles of the subject from granted authorities, as
 * the Spring Boot starter sends them.
 *
 * <p>The policy grants {@code read} to viewer, editor and admin, {@code update} and {@code delete}
 * to admin, and {@code update} to an editor of the document's department. alice is an admin and
 * bob an editor, both of sales; carol is a viewer; dave has no role. {@code doc-1} belongs to
 * sales, {@code doc-2} to hr.
 */
class CerbosEvaluationTest {

    private static final RoleMapping ROLES = RoleMapping.toProperty("cerbos.roles").prefix("ROLE_").build();
    private static final Map<String, List<String>> AUTHORITIES = Map.of(
            "alice", List.of("ROLE_admin", "SCOPE_documents"),
            "bob", List.of("ROLE_editor", "SCOPE_documents"),
            "carol", List.of("ROLE_viewer"),
            "dave", List.of("SCOPE_documents"));

    private static CerbosPdp pdp;
    private static AuthZenClient client;

    @BeforeAll
    static void connect() {
        pdp = CerbosPdp.get();
        // No credentials: Cerbos's AuthZEN API does not authenticate the PEP
        client = AuthZenClient.builder().pdpUrl(pdp.url()).build();
    }

    static Subject user(String name) {
        Subject subject = Subject.builder().type("user").id(name)
                .property("department", "carol".equals(name) || "dave".equals(name) ? "hr" : "sales")
                .build();
        return ROLES.apply(subject, AUTHORITIES.get(name));
    }

    static Resource document(String id) {
        return Resource.builder().type("document").id(id).property("department", "doc-1".equals(id) ? "sales" : "hr")
                .build();
    }

    private static boolean decide(Subject subject, String action, String document) {
        return client.evaluate(EvaluationRequest.of(subject, Action.of(action), document(document))).getDecision();
    }

    @Test
    void findsTheEndpointsInTheMetadata() {
        PdpMetadata metadata = client.metadata().orElseThrow();

        assertEquals(pdp.url().toString(), metadata.getPolicyDecisionPoint());
        assertEquals(URI.create(pdp.url() + "/access/v1/evaluation"), metadata.getAccessEvaluationEndpoint());
        assertEquals(URI.create(pdp.url() + "/access/v1/evaluations"),
                metadata.getAccessEvaluationsEndpoint().orElseThrow());
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource({
            "alice, read,   doc-1, true",
            "alice, update, doc-2, true",
            "alice, delete, doc-2, true",
            "bob,   read,   doc-2, true",
            "bob,   update, doc-1, true",
            "bob,   update, doc-2, false",
            "bob,   delete, doc-1, false",
            "carol, read,   doc-1, true",
            "carol, update, doc-2, false",
            "dave,  read,   doc-1, false"
    })
    void decidesByTheRolesAndAttributes(String name, String action, String document, boolean expected) {
        assertEquals(expected, decide(user(name), action, document));
    }

    @Test
    void sendsTheRolesUnderTheFlatName() {
        Subject bob = user("bob");

        assertEquals(List.of("editor"), bob.getProperties().get("cerbos.roles"));
        assertTrue(decide(bob, "read", "doc-1"));
    }

    @Test
    void takesNoRolesFromANestedObject() {
        Subject nested = Subject.builder().type("user").id("bob").property("department", "sales")
                .property("cerbos", Map.of("roles", List.of("editor"))).build();

        // An ordinary attribute to Cerbos, so bob has no role
        assertFalse(decide(nested, "read", "doc-1"));
    }

    @Test
    void deniesACallerWithoutRoles() {
        Subject dave = user("dave");

        assertFalse(dave.getProperties().containsKey("cerbos.roles"));
        assertFalse(decide(dave, "read", "doc-1"));
    }

    @Test
    void deniesAnUnknownResourceType() {
        assertFalse(client.evaluate(EvaluationRequest.of(user("alice"), Action.of("read"),
                Resource.of("spreadsheet", "s-1"))).getDecision());
    }
}
