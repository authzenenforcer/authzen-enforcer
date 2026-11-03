package io.github.authzenenforcer.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class EvaluationsRequestTest {

    private static final Subject ALICE = Subject.of("user", "alice@example.com");
    private static final Action READ = Action.of("can_read");

    @Test
    void takesMissingItemPartsFromTheDefaults() {
        EvaluationsRequest request = EvaluationsRequest.builder()
                .subject(ALICE)
                .action(READ)
                .evaluation(EvaluationItem.builder().resource(Resource.of("document", "a")).build())
                .evaluation(EvaluationItem.builder().action(Action.of("can_edit")).resource(Resource.of("document", "b")).build())
                .semantic(EvaluationsSemantic.DENY_ON_FIRST_DENY)
                .build();

        assertEquals(Optional.of(ALICE), request.getSubject());
        assertEquals(2, request.getEvaluations().size());
        assertEquals(Optional.empty(), request.getEvaluations().get(0).getAction());
        assertEquals(Optional.of(EvaluationsSemantic.DENY_ON_FIRST_DENY), request.getSemantic());
    }

    @Test
    void acceptsItemsThatSetEverything() {
        EvaluationsRequest request = EvaluationsRequest.builder()
                .evaluation(EvaluationItem.of(ALICE, READ, Resource.of("document", "a")))
                .build();

        assertEquals(Optional.empty(), request.getSubject());
        assertEquals(Optional.empty(), request.getSemantic(), "the PDP default applies");
    }

    @Test
    void requiresEveryItemToEndUpWithSubjectActionAndResource() {
        EvaluationsRequest.Builder noResource = EvaluationsRequest.builder()
                .subject(ALICE).action(READ)
                .evaluation(EvaluationItem.builder().resource(Resource.of("document", "a")).build())
                .evaluation(EvaluationItem.builder().build());

        IllegalStateException e = assertThrows(IllegalStateException.class, noResource::build);
        assertTrue(e.getMessage().contains("evaluation 1"), e.getMessage());
    }

    @Test
    void requiresAllDefaultsWithoutItems() {
        assertThrows(IllegalStateException.class, () -> EvaluationsRequest.builder().subject(ALICE).action(READ).build());

        EvaluationsRequest single = EvaluationsRequest.builder()
                .subject(ALICE).action(READ).resource(Resource.of("document", "a")).build();
        assertTrue(single.getEvaluations().isEmpty());
    }

    @Test
    void keepsTheSemanticOutOfTheOtherOptions() {
        EvaluationsRequest.Builder builder = EvaluationsRequest.builder()
                .evaluation(EvaluationItem.of(ALICE, READ, Resource.of("document", "a")))
                .option("evaluations_semantic", "execute_all");

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void masksOptionValuesInToString() {
        EvaluationsRequest request = EvaluationsRequest.builder()
                .evaluation(EvaluationItem.of(ALICE, READ, Resource.of("document", "a")))
                .option("tenant", "secret-tenant")
                .build();

        assertTrue(request.toString().contains("options={tenant=***}"), request.toString());
        assertTrue(!request.toString().contains("alice@example.com"), "the subject id is masked");
    }

    @Test
    void implementsValueEquality() {
        EvaluationsRequest a = EvaluationsRequest.builder()
                .evaluations(List.of(EvaluationItem.of(ALICE, READ, Resource.of("document", "a"))))
                .option("x", Map.of("y", 1))
                .build();
        EvaluationsRequest b = EvaluationsRequest.builder()
                .evaluation(EvaluationItem.of(ALICE, READ, Resource.of("document", "a")))
                .option("x", Map.of("y", 1))
                .build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, EvaluationsRequest.builder()
                .evaluation(EvaluationItem.of(ALICE, READ, Resource.of("document", "a")))
                .semantic(EvaluationsSemantic.EXECUTE_ALL)
                .build());
    }
}
