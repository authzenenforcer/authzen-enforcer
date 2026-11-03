package io.github.authzenenforcer.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationItem;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.EvaluationsSemantic;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Access Evaluations API against Keycloak, with the endpoint taken from its metadata.
 *
 * <p>The realm grants {@code read} to every role, {@code update} to admin and editor, and
 * {@code delete} to admin only; bob is an editor and dave has no role.
 */
class KeycloakEvaluationsTest {

    private static AuthZenClient client;

    @BeforeAll
    static void connect() {
        KeycloakPdp pdp = KeycloakPdp.get();
        client = AuthZenClient.builder()
                .pdpUrl(pdp.realmUrl())
                .credentials(ClientCredentialsProvider.forPep(pdp))
                .build();
    }

    private static EvaluationItem action(String name) {
        return EvaluationItem.builder().action(Action.of(name)).build();
    }

    private static EvaluationsRequest.Builder bobOnDoc1(String... actions) {
        EvaluationsRequest.Builder builder = EvaluationsRequest.builder()
                .subject(Subject.of("user", "bob"))
                .resource(Resource.of("document", "doc-1"));
        for (String name : actions) {
            builder.evaluation(action(name));
        }
        return builder;
    }

    @Test
    void returnsEveryDecisionInOrder() {
        EvaluationsResponse response = client.evaluations(bobOnDoc1("read", "update", "delete").build());

        assertEquals(List.of(true, true, false),
                response.getEvaluations().stream().map(decision -> decision.getDecision()).toList());
    }

    @Test
    void stopsAtTheFirstDeny() {
        EvaluationsResponse response = client.evaluations(bobOnDoc1("read", "delete", "update")
                .semantic(EvaluationsSemantic.DENY_ON_FIRST_DENY).build());

        assertEquals(2, response.getEvaluations().size());
        assertFalse(response.isPermitted(1));
        assertFalse(response.isPermitted(2), "the item after the deny was not evaluated");
        assertEquals(Map.of("reason", "deny_on_first_deny"),
                response.get(1).orElseThrow().getContext().orElseThrow().asMap());
    }

    @Test
    void stopsAtTheFirstPermit() {
        EvaluationsRequest request = EvaluationsRequest.builder()
                .action(Action.of("read"))
                .resource(Resource.of("document", "doc-1"))
                .evaluation(EvaluationItem.builder().subject(Subject.of("user", "dave")).build())
                .evaluation(EvaluationItem.builder().subject(Subject.of("user", "carol")).build())
                .evaluation(EvaluationItem.builder().subject(Subject.of("user", "alice")).build())
                .semantic(EvaluationsSemantic.PERMIT_ON_FIRST_PERMIT)
                .build();

        EvaluationsResponse response = client.evaluations(request);

        assertEquals(2, response.getEvaluations().size());
        assertTrue(response.anyPermitted());
        assertFalse(response.allPermitted());
    }

    @Test
    void letsItemsOverrideTheDefaultsAsAWhole() {
        EvaluationsRequest request = EvaluationsRequest.builder()
                .subject(Subject.of("user", "alice"))
                .action(Action.of("delete"))
                .evaluation(EvaluationItem.builder().resource(Resource.of("document", "doc-1")).build())
                .evaluation(EvaluationItem.builder().subject(Subject.of("user", "bob"))
                        .resource(Resource.of("document", "doc-2")).build())
                .build();

        EvaluationsResponse response = client.evaluations(request);

        assertTrue(response.isPermitted(0), "alice may delete");
        assertFalse(response.isPermitted(1), "bob may not");
    }

    @Test
    void answersARequestWithoutItemsWithASingleDecision() {
        EvaluationsResponse response = client.evaluations(EvaluationsRequest.builder()
                .subject(Subject.of("user", "carol")).action(Action.of("read")).resource(Resource.of("document", "doc-2"))
                .build());

        assertTrue(response.allPermitted());
        assertEquals(1, response.getEvaluations().size());
    }
}
