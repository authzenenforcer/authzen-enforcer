package io.github.authzenenforcer.core.internal.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Round trips against the examples of the AuthZEN Authorization API 1.0 specification.
 */
class WireFormatTest {

    private final JsonCodec codec = JsonCodec.defaultCodec();

    @Nested
    class EvaluationRequests {

        @Test
        void writesSpecificationRequestExample() {
            String expected = """
                    {
                      "subject": {
                        "type": "user",
                        "id": "alice@example.com"
                      },
                      "action": {
                        "name": "can_read",
                        "properties": {
                          "method": "GET"
                        }
                      },
                      "resource": {
                        "type": "account",
                        "id": "123"
                      },
                      "context": {
                        "time": "1985-10-26T01:22-07:00"
                      }
                    }
                    """;
            EvaluationRequest request = EvaluationRequest.builder()
                    .subject(Subject.of("user", "alice@example.com"))
                    .action(Action.builder().name("can_read").property("method", "GET").build())
                    .resource(Resource.of("account", "123"))
                    .context(Context.of(Map.of("time", "1985-10-26T01:22-07:00")))
                    .build();

            assertWritten(expected, request);
        }

        @Test
        void writesEntityPropertiesFromSpecificationExamples() {
            String expected = """
                    {
                      "subject": {
                        "type": "user",
                        "id": "alice@example.com",
                        "properties": {
                          "department": "Sales",
                          "ip_address": "172.217.22.14",
                          "device_id": "8:65:ee:17:7e:0b"
                        }
                      },
                      "action": {
                        "name": "extend-loan",
                        "properties": {
                          "period": "2W"
                        }
                      },
                      "resource": {
                        "type": "book",
                        "id": "123",
                        "properties": {
                          "library_record": {
                            "title": "AuthZEN in Action",
                            "isbn": "978-0593383322"
                          }
                        }
                      }
                    }
                    """;
            EvaluationRequest request = EvaluationRequest.of(
                    Subject.builder().type("user").id("alice@example.com")
                            .property("department", "Sales")
                            .property("ip_address", "172.217.22.14")
                            .property("device_id", "8:65:ee:17:7e:0b")
                            .build(),
                    Action.builder().name("extend-loan").property("period", "2W").build(),
                    Resource.builder().type("book").id("123")
                            .property("library_record", Map.of("title", "AuthZEN in Action", "isbn", "978-0593383322"))
                            .build());

            assertWritten(expected, request);
        }

        @Test
        void writesFieldsInSpecificationOrderAndOmitsEmptyOptionals() {
            EvaluationRequest request = EvaluationRequest.of(
                    Subject.of("user", "alice"), Action.of("read"), Resource.of("document", "1"));

            assertEquals("{\"subject\":{\"type\":\"user\",\"id\":\"alice\"},\"action\":{\"name\":\"read\"},"
                    + "\"resource\":{\"type\":\"document\",\"id\":\"1\"}}", codec.write(WireFormat.evaluationRequest(request)));
        }

        @Test
        void writesEmptyContextWhenSetExplicitly() {
            EvaluationRequest request = EvaluationRequest.builder()
                    .subject(Subject.of("user", "alice")).action(Action.of("read"))
                    .resource(Resource.of("document", "1")).context(Context.empty())
                    .build();

            assertEquals(Map.of(), WireFormat.evaluationRequest(request).get("context"));
        }

        @Test
        void keepsPropertyValuesThroughRoundTrip() {
            Subject subject = Subject.builder().type("user").id("alice")
                    .property("level", 3)
                    .property("score", 0.75)
                    .property("roles", List.of("admin", "user"))
                    .property("manager", null)
                    .build();
            EvaluationRequest request = EvaluationRequest.of(subject, Action.of("read"), Resource.of("document", "1"));

            Object parsed = codec.read(codec.write(WireFormat.evaluationRequest(request)));

            assertEquals(subject.getProperties(), ((Map<?, ?>) ((Map<?, ?>) parsed).get("subject")).get("properties"));
        }

        private void assertWritten(String expected, EvaluationRequest request) {
            Map<String, Object> json = WireFormat.evaluationRequest(request);
            assertEquals(codec.read(expected), json);
            assertEquals(codec.read(expected), codec.read(codec.write(json)));
            assertEquals(keyOrder(codec.read(expected)), keyOrder(json));
        }

        // Ordering is not significant on the wire, but writing in specification order keeps output readable
        private List<String> keyOrder(Object json) {
            return new ArrayList<>(((Map<String, ?>) json).keySet());
        }
    }

    @Nested
    class EvaluationResponses {

        @Test
        void readsSpecificationPermitExample() {
            EvaluationResponse response = read("""
                    {
                      "decision": true
                    }
                    """);

            assertEquals(EvaluationResponse.of(true), response);
        }

        @Test
        void readsSpecificationReasonExample() {
            EvaluationResponse response = read("""
                    {
                      "decision": false,
                      "context": {
                        "reason_admin": {
                          "403": "Request failed policy C076E82F"
                        },
                        "reason_user": {
                          "403": "Insufficient privileges. Contact your administrator"
                        }
                      }
                    }
                    """);

            assertFalse(response.getDecision());
            assertEquals(Map.of(
                    "reason_admin", Map.of("403", "Request failed policy C076E82F"),
                    "reason_user", Map.of("403", "Insufficient privileges. Contact your administrator")),
                    response.getContext().orElseThrow().asMap());
        }

        @Test
        void readsContextWithAnyJsonValues() {
            EvaluationResponse response = read("""
                    {"decision": false, "context": {"metadata": {"response_time": 60, "response_time_unit": "ms"},
                     "acr_values": ["urn:example:mfa"], "partial": null}}
                    """);

            Map<String, Object> context = response.getContext().orElseThrow().asMap();
            assertEquals(Map.of("response_time", 60L, "response_time_unit", "ms"), context.get("metadata"));
            assertEquals(List.of("urn:example:mfa"), context.get("acr_values"));
            assertTrue(context.containsKey("partial"));
        }

        @Test
        void ignoresUnknownFields() {
            assertEquals(EvaluationResponse.of(true), read("{\"decision\": true, \"extension\": {\"x\": 1}}"));
        }

        @Test
        void treatsNullContextAsAbsent() {
            assertEquals(EvaluationResponse.of(true), read("{\"decision\": true, \"context\": null}"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "[]",
                "true",
                "null",
                "\"decision\"",
                "{}",
                "{\"decision\": null}",
                "{\"decision\": \"true\"}",
                "{\"decision\": 1}",
                "{\"decision\": [true]}",
                "{\"Decision\": true}",
                "{\"decision\": true, \"context\": []}",
                "{\"decision\": true, \"context\": \"reason\"}"
        })
        void rejectsInvalidResponses(String json) {
            assertThrows(PdpProtocolException.class, () -> read(json));
        }

        @Test
        void rejectsContextWithValuesTheModelCannotHold() {
            // A custom codec may produce types the built-in parser never returns
            Map<String, Object> json = Map.of("decision", true, "context", Map.of("x", new Object()));
            assertThrows(PdpProtocolException.class, () -> WireFormat.evaluationResponse(json));
        }

        @Test
        void doesNotIncludeResponseContentInErrorMessages() {
            PdpProtocolException e = assertThrows(PdpProtocolException.class,
                    () -> read("{\"decision\": \"secret-value\"}"));
            assertFalse(e.getMessage().contains("secret-value"));
        }

        private EvaluationResponse read(String json) {
            return WireFormat.evaluationResponse(codec.read(json));
        }
    }
}
