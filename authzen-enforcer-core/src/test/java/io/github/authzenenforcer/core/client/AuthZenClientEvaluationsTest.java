package io.github.authzenenforcer.core.client;

import static io.github.authzenenforcer.core.client.FakePdp.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.FakePdp.RecordedRequest;
import io.github.authzenenforcer.core.internal.client.TestLoggerFinder;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationItem;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.EvaluationsSemantic;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthZenClientEvaluationsTest {

    private static final String TOKEN = "token-1";
    private static final EvaluationsRequest REQUEST = EvaluationsRequest.builder()
            .subject(Subject.of("user", "alice"))
            .action(Action.of("read"))
            .evaluation(EvaluationItem.builder().resource(Resource.of("document", "a")).build())
            .evaluation(EvaluationItem.builder().resource(Resource.of("document", "b")).build())
            .build();

    private FakePdp pdp;

    @BeforeEach
    void startPdp() throws IOException {
        pdp = FakePdp.start();
    }

    @AfterEach
    void stopPdp() {
        pdp.close();
    }

    private AuthZenClient.Builder builder() {
        return AuthZenClient.builder()
                .pdpUrl(pdp.url())
                .credentials(CredentialProvider.staticBearer(TOKEN))
                .requestTimeout(Duration.ofSeconds(2))
                .maxRetries(0);
    }

    private void publish(String evaluationsEndpoint) {
        String endpoints = "\"access_evaluation_endpoint\": \"" + pdp.url() + "/e\""
                + (evaluationsEndpoint == null ? "" : ", \"access_evaluations_endpoint\": \"" + pdp.url() + evaluationsEndpoint + "\"");
        pdp.respondToMetadata(json(200, "{\"policy_decision_point\": \"" + pdp.url() + "\", " + endpoints + "}"));
    }

    private RecordedRequest onlyRequest() {
        List<RecordedRequest> requests = pdp.requests();
        assertEquals(1, requests.size());
        return requests.get(0);
    }

    @Test
    void postsTheBatchToTheDefaultPathWithoutMetadata() {
        pdp.respond(json(200, "{\"evaluations\": [{\"decision\": true}, {\"decision\": false}]}"));

        EvaluationsResponse response = builder().build().evaluations(REQUEST);

        assertTrue(response.isPermitted(0));
        assertFalse(response.isPermitted(1));
        RecordedRequest request = onlyRequest();
        assertEquals("POST", request.method());
        assertEquals("/access/v1/evaluations", request.path());
        assertEquals("Bearer " + TOKEN, request.header("Authorization"));
        assertEquals(Map.of(
                "subject", Map.of("type", "user", "id", "alice"),
                "action", Map.of("name", "read"),
                "evaluations", List.of(
                        Map.of("resource", Map.of("type", "document", "id", "a")),
                        Map.of("resource", Map.of("type", "document", "id", "b")))),
                JsonCodec.defaultCodec().read(request.body()));
    }

    @Test
    void postsTheBatchToTheDiscoveredEndpoint() {
        publish("/authzen/access/v1/evaluations");
        pdp.respond(json(200, "{\"evaluations\": [{\"decision\": true}, {\"decision\": true}]}"));

        assertTrue(builder().build().evaluations(REQUEST).allPermitted());

        assertEquals("/authzen/access/v1/evaluations", onlyRequest().path());
    }

    @Test
    void failsWithoutContactingAPdpThatDoesNotOfferTheApi() {
        publish(null);

        PdpProtocolException e = assertThrows(PdpProtocolException.class, () -> builder().build().evaluations(REQUEST));

        assertTrue(e.getMessage().contains("does not offer the Access Evaluations API"), e.getMessage());
        assertTrue(pdp.requests().isEmpty());
    }

    @Test
    void usesTheConfiguredEndpointWithoutDiscovery() {
        publish(null);
        pdp.respond(json(200, "{\"evaluations\": [{\"decision\": true}, {\"decision\": true}]}"));

        builder().evaluationsEndpoint(URI.create(pdp.url() + "/custom/evaluations")).build().evaluations(REQUEST);

        assertEquals("/custom/evaluations", onlyRequest().path());
        assertTrue(pdp.metadataRequests().isEmpty());
    }

    @Test
    void treatsTheItemsAPdpSkippedAsDenies() {
        pdp.respond(json(200, "{\"evaluations\": [{\"decision\": false}]}"));
        EvaluationsRequest request = EvaluationsRequest.builder()
                .subject(Subject.of("user", "alice")).action(Action.of("read"))
                .evaluations(REQUEST.getEvaluations())
                .semantic(EvaluationsSemantic.DENY_ON_FIRST_DENY)
                .build();

        EvaluationsResponse response = builder().build().evaluationsAsync(request).toCompletableFuture().join();

        assertEquals(1, response.getEvaluations().size());
        assertFalse(response.isPermitted(1));
        assertEquals(Map.of("evaluations_semantic", "deny_on_first_deny"),
                ((Map<?, ?>) JsonCodec.defaultCodec().read(onlyRequest().body())).get("options"));
    }

    @Test
    void rejectsAnAnswerWithMissingDecisionsForExecuteAll() {
        pdp.respond(json(200, "{\"evaluations\": [{\"decision\": true}]}"));

        assertThrows(PdpProtocolException.class, () -> builder().build().evaluations(REQUEST));
    }

    @Test
    void failsClosedWhenThePdpIsDown() {
        pdp.respond(json(503, ""));

        assertThrows(PdpCommunicationException.class, () -> builder().build().evaluations(REQUEST));
    }

    @Test
    void warnsAboutPlainHttpOnTheDefaultBatchPath() {
        TestLoggerFinder.clearRecords();

        AuthZenClient.builder()
                .pdpUrl("http://pdp.example.com")
                .evaluationEndpoint(URI.create("https://gateway.example.com/evaluation"))
                .credentials(CredentialProvider.staticBearer(TOKEN))
                .discovery(false)
                .build();

        assertTrue(TestLoggerFinder.records().stream().anyMatch(record -> record.startsWith("WARNING")
                        && record.contains("http://pdp.example.com uses plain HTTP")),
                String.valueOf(TestLoggerFinder.records()));
    }

    @Test
    void sharesTheCircuitBreakerWithSingleEvaluations() {
        pdp.respond(json(503, ""));
        AuthZenClient client = builder().build();
        for (int i = 0; i < CircuitBreakerOptions.DEFAULT_FAILURE_THRESHOLD; i++) {
            assertThrows(PdpCommunicationException.class, () -> client.evaluations(REQUEST));
        }

        PdpCommunicationException e = assertThrows(PdpCommunicationException.class, () -> client.evaluate(
                EvaluationRequest.of(
                        Subject.of("user", "alice"), Action.of("read"), Resource.of("document", "a"))));

        assertTrue(e.getMessage().startsWith("PDP evaluation is suspended"), e.getMessage());
        assertEquals(CircuitBreakerOptions.DEFAULT_FAILURE_THRESHOLD, pdp.requests().size());
    }
}
