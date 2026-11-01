package io.github.authzenenforcer.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpAuthenticationException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Access Evaluation API against Keycloak.
 *
 * <p>The realm grants {@code read} to every role, {@code update} to admin and editor, and
 * {@code delete} to admin only. Since the client rejects responses that echo a different
 * {@code X-Request-ID}, every passing call also checks the echo.
 */
class KeycloakEvaluationTest {

    private static KeycloakPdp pdp;
    private static AuthZenClient client;

    @BeforeAll
    static void connect() {
        pdp = KeycloakPdp.get();
        client = clientWith(ClientCredentialsProvider.forPep(pdp));
    }

    private static AuthZenClient clientWith(CredentialProvider credentials) {
        return AuthZenClient.builder()
                .pdpUrl(pdp.realmUrl())
                .evaluationEndpoint(pdp.evaluationEndpoint())
                .credentials(credentials)
                .build();
    }

    private static EvaluationRequest request(String subject, String action, String document) {
        return EvaluationRequest.of(Subject.of("user", subject), Action.of(action), Resource.of("document", document));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "alice, read,   true",
            "alice, update, true",
            "alice, delete, true",
            "bob,   read,   true",
            "bob,   update, true",
            "bob,   delete, false",
            "carol, read,   true",
            "carol, update, false",
            "carol, delete, false",
            "dave,  read,   false",
            "dave,  update, false",
            "dave,  delete, false"
    })
    void decidesByRole(String subject, String action, boolean expected) {
        assertEquals(expected, client.evaluate(request(subject, action, "doc-1")).getDecision());
    }

    @Test
    void resolvesSubjectsByEmail() {
        assertTrue(client.evaluate(request("email:alice@example.com", "delete", "doc-2")).getDecision());
    }

    @Test
    void deniesUnknownSubjectsResourcesAndActions() {
        assertFalse(client.evaluate(request("nobody", "read", "doc-1")).getDecision());
        assertFalse(client.evaluate(request("alice", "read", "doc-9")).getDecision());
        assertFalse(client.evaluate(request("alice", "archive", "doc-1")).getDecision());
        assertFalse(client.evaluate(EvaluationRequest.of(
                Subject.of("user", "alice"), Action.of("read"), Resource.of("file", "doc-1"))).getDecision());
    }

    @Test
    void evaluatesAsynchronously() {
        assertTrue(client.evaluateAsync(request("carol", "read", "doc-2")).join().getDecision());
    }

    @Test
    void refreshesARejectedTokenAndRetries() {
        ClientCredentialsProvider valid = ClientCredentialsProvider.forPep(pdp);
        AccessCredential expired = AccessCredential.bearer("expired-token");
        AtomicInteger invalidations = new AtomicInteger();
        CredentialProvider credentials = new CredentialProvider() {
            private volatile boolean rejected;

            @Override
            public AccessCredential getCredential() {
                return rejected ? valid.getCredential() : expired;
            }

            @Override
            public void invalidate(AccessCredential credential) {
                invalidations.incrementAndGet();
                rejected = true;
            }
        };

        assertTrue(clientWith(credentials).evaluate(request("alice", "read", "doc-1")).getDecision());
        assertEquals(1, invalidations.get());
    }

    @Test
    void failsClosedWhenThePepTokenIsRejected() {
        AuthZenClient rejected = clientWith(CredentialProvider.staticBearer("not-a-keycloak-token"));

        PdpAuthenticationException e = assertThrows(PdpAuthenticationException.class,
                () -> rejected.evaluate(request("alice", "read", "doc-1")));
        assertEquals(401, e.getStatusCode());
    }

    @Test
    void failsClosedOnSubjectTypesKeycloakDoesNotKnow() {
        EvaluationRequest group = EvaluationRequest.of(
                Subject.of("group", "admins"), Action.of("read"), Resource.of("document", "doc-1"));

        assertThrows(PdpProtocolException.class, () -> client.evaluate(group));
    }

    @Test
    void failsClosedOnTheDefaultPathBecauseKeycloakServesAuthZenElsewhere() {
        AuthZenClient defaultPath = AuthZenClient.builder()
                .pdpUrl(pdp.realmUrl())
                .credentials(ClientCredentialsProvider.forPep(pdp))
                .build();

        assertThrows(PdpProtocolException.class, () -> defaultPath.evaluate(request("alice", "read", "doc-1")));
    }
}
