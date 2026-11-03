package io.github.authzenenforcer.core.internal.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationItem;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.EvaluationsSemantic;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Access Evaluations requests and responses, checked against the examples of AuthZEN 1.0 section 7.
 */
class WireFormatEvaluationsTest {

    private final JsonCodec codec = JsonCodec.defaultCodec();

    private static EvaluationsRequest threeDocuments(EvaluationsSemantic semantic) {
        EvaluationsRequest.Builder builder = EvaluationsRequest.builder()
                .subject(Subject.of("user", "alice@example.com"))
                .action(Action.of("can_read"))
                .evaluation(EvaluationItem.builder().resource(Resource.of("document", "boxcarring.md")).build())
                .evaluation(EvaluationItem.builder().resource(Resource.of("document", "subject-search.md")).build())
                .evaluation(EvaluationItem.builder().action(Action.of("can_edit"))
                        .resource(Resource.of("document", "resource-search.md")).build());
        if (semantic != null) {
            builder.semantic(semantic);
        }
        return builder.build();
    }

    private EvaluationsResponse read(String json, EvaluationsRequest request) {
        return WireFormat.evaluationsResponse(codec.read(json), request);
    }

    @Test
    void writesDefaultsItemsAndOptionsAsSet() {
        String expected = """
                {
                  "subject": {"type": "user", "id": "alice@example.com"},
                  "action": {"name": "can_read"},
                  "evaluations": [
                    {"resource": {"type": "document", "id": "boxcarring.md"}},
                    {"resource": {"type": "document", "id": "subject-search.md"}},
                    {"action": {"name": "can_edit"}, "resource": {"type": "document", "id": "resource-search.md"}}
                  ],
                  "options": {"evaluations_semantic": "deny_on_first_deny"}
                }
                """;

        assertEquals(codec.read(expected),
                codec.read(codec.write(WireFormat.evaluationsRequest(threeDocuments(EvaluationsSemantic.DENY_ON_FIRST_DENY)))));
    }

    @Test
    void omitsOptionsWithoutSemanticAndWritesOtherOptions() {
        assertFalse(WireFormat.evaluationsRequest(threeDocuments(null)).containsKey("options"));

        EvaluationsRequest request = EvaluationsRequest.builder()
                .subject(Subject.of("user", "alice")).action(Action.of("read")).resource(Resource.of("document", "a"))
                .context(Context.of(Map.of("time", "1985-10-26T01:22-07:00")))
                .option("another_option", "value")
                .build();
        Map<String, Object> json = WireFormat.evaluationsRequest(request);

        assertEquals(Map.of("another_option", "value"), json.get("options"));
        assertFalse(json.containsKey("evaluations"), "no items means a single evaluation of the defaults");
        assertEquals(Map.of("time", "1985-10-26T01:22-07:00"), json.get("context"));
    }

    @Test
    void readsAllDecisionsInOrder() {
        EvaluationsResponse response = read("""
                {"evaluations": [{"decision": true}, {"decision": false, "context": {"reason": "not owner"}},
                 {"decision": true}]}
                """, threeDocuments(null));

        assertTrue(response.isPermitted(0));
        assertFalse(response.isPermitted(1));
        assertEquals("not owner", response.get(1).orElseThrow().getContext().orElseThrow().asMap().get("reason"));
        assertTrue(response.isPermitted(2));
    }

    @Test
    void readsAPerItemErrorAsADeny() {
        EvaluationsResponse response = read("""
                {"evaluations": [{"decision": true},
                 {"decision": false, "context": {"error": {"status": 404, "message": "Resource not found"}}},
                 {"decision": true}]}
                """, threeDocuments(EvaluationsSemantic.EXECUTE_ALL));

        assertFalse(response.isPermitted(1));
        assertFalse(response.allPermitted());
    }

    @Test
    void acceptsAShortAnswerWhenThePdpMayStopEarly() {
        EvaluationsResponse denied = read("{\"evaluations\": [{\"decision\": true}, {\"decision\": false}]}",
                threeDocuments(EvaluationsSemantic.DENY_ON_FIRST_DENY));
        EvaluationsResponse permitted = read("{\"evaluations\": [{\"decision\": true}]}",
                threeDocuments(EvaluationsSemantic.PERMIT_ON_FIRST_PERMIT));

        assertEquals(2, denied.getEvaluations().size());
        assertFalse(denied.isPermitted(2));
        assertTrue(permitted.anyPermitted());
        assertFalse(permitted.allPermitted());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{}",
            "{\"decision\": true}",
            "{\"evaluations\": {\"decision\": true}}",
            "{\"evaluations\": [{\"decision\": true}, {\"decision\": true}]}",
            "{\"evaluations\": [{\"decision\": true}, {\"decision\": true}, {\"decision\": true}, {\"decision\": true}]}",
            "{\"evaluations\": [{\"decision\": true}, {\"decision\": \"false\"}, {\"decision\": true}]}",
            "{\"evaluations\": [{\"decision\": true}, null, {\"decision\": true}]}"
    })
    void rejectsAnswersThatDoNotMatchAnExecuteAllRequest(String json) {
        assertThrows(PdpProtocolException.class, () -> read(json, threeDocuments(null)));
    }

    @Test
    void rejectsMoreDecisionsThanItemsEvenWhenThePdpMayStopEarly() {
        assertThrows(PdpProtocolException.class, () -> read(
                "{\"evaluations\": [{\"decision\": true}, {\"decision\": true}, {\"decision\": true}, {\"decision\": false}]}",
                threeDocuments(EvaluationsSemantic.DENY_ON_FIRST_DENY)));
    }

    @Test
    void readsASingleDecisionForARequestWithoutItems() {
        EvaluationsRequest single = EvaluationsRequest.builder()
                .subject(Subject.of("user", "alice")).action(Action.of("read")).resource(Resource.of("document", "a"))
                .build();

        assertTrue(read("{\"decision\": true}", single).allPermitted());
        assertTrue(read("{\"evaluations\": [{\"decision\": true}]}", single).allPermitted());
    }

    @Test
    void prefersTheEvaluationsOverATopLevelDecision() {
        EvaluationsResponse response = read(
                "{\"decision\": true, \"evaluations\": [{\"decision\": false}, {\"decision\": false}, {\"decision\": false}]}",
                threeDocuments(null));

        assertFalse(response.anyPermitted());
    }
}
