package io.github.authzenenforcer.interop;

import static io.github.authzenenforcer.interop.CerbosEvaluationTest.document;
import static io.github.authzenenforcer.interop.CerbosEvaluationTest.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationItem;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.EvaluationsSemantic;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Access Evaluations API against Cerbos, with the endpoint taken from its metadata; see
 * {@link CerbosEvaluationTest} for the policy and the callers.
 */
class CerbosEvaluationsTest {

    private static AuthZenClient client;

    @BeforeAll
    static void connect() {
        client = AuthZenClient.builder().pdpUrl(CerbosPdp.get().url()).build();
    }

    private static EvaluationsRequest.Builder bobOnDoc2(String... actions) {
        EvaluationsRequest.Builder builder = EvaluationsRequest.builder()
                .subject(user("bob"))
                .resource(document("doc-2"));
        for (String name : actions) {
            builder.evaluation(EvaluationItem.builder().action(Action.of(name)).build());
        }
        return builder;
    }

    @Test
    void returnsEveryDecisionInOrder() {
        EvaluationsResponse response = client.evaluations(bobOnDoc2("read", "update", "delete").build());

        assertEquals(List.of(true, false, false),
                response.getEvaluations().stream().map(decision -> decision.getDecision()).toList());
    }

    @Test
    void stopsAtTheFirstDeny() {
        EvaluationsResponse response = client.evaluations(bobOnDoc2("read", "update", "delete")
                .semantic(EvaluationsSemantic.DENY_ON_FIRST_DENY).build());

        // The delete after the denied update is not evaluated
        assertEquals(List.of(true, false),
                response.getEvaluations().stream().map(decision -> decision.getDecision()).toList());
        assertFalse(response.allPermitted());
    }

    @Test
    void stopsAtTheFirstPermit() {
        EvaluationsRequest request = EvaluationsRequest.builder()
                .action(Action.of("read"))
                .resource(document("doc-1"))
                .evaluation(EvaluationItem.builder().subject(user("dave")).build())
                .evaluation(EvaluationItem.builder().subject(user("carol")).build())
                .evaluation(EvaluationItem.builder().subject(user("alice")).build())
                .semantic(EvaluationsSemantic.PERMIT_ON_FIRST_PERMIT)
                .build();

        EvaluationsResponse response = client.evaluations(request);

        // alice, after carol's permit, is not evaluated
        assertEquals(List.of(false, true),
                response.getEvaluations().stream().map(decision -> decision.getDecision()).toList());
        assertTrue(response.anyPermitted());
    }

    @Test
    void letsItemsOverrideTheDefaults() {
        EvaluationsRequest request = EvaluationsRequest.builder()
                .subject(user("bob"))
                .action(Action.of("update"))
                .evaluation(EvaluationItem.builder().resource(document("doc-1")).build())
                .evaluation(EvaluationItem.builder().resource(document("doc-2")).build())
                .evaluation(EvaluationItem.builder().subject(user("alice")).resource(document("doc-2")).build())
                .build();

        EvaluationsResponse response = client.evaluations(request);

        assertTrue(response.isPermitted(0), "bob updates a document of his department");
        assertFalse(response.isPermitted(1), "but not one of another");
        assertTrue(response.isPermitted(2), "alice is an admin");
    }
}
