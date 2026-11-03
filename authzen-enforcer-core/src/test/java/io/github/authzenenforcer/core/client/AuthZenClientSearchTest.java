package io.github.authzenenforcer.core.client;

import static io.github.authzenenforcer.core.client.FakePdp.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.FakePdp.RecordedRequest;
import io.github.authzenenforcer.core.internal.client.TestLoggerFinder;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.ActionSearchRequest;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.PageRequest;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.ResourceSearchRequest;
import io.github.authzenenforcer.core.model.SearchResponse;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.model.SubjectSearchRequest;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthZenClientSearchTest {

    private static final String TOKEN = "token-1";
    private static final Subject ALICE = Subject.of("user", "alice");
    private static final Resource ACCOUNT = Resource.of("account", "123");
    private static final SubjectSearchRequest SUBJECTS =
            SubjectSearchRequest.of("user", Action.of("can_read"), ACCOUNT);
    private static final ResourceSearchRequest RESOURCES =
            ResourceSearchRequest.of(ALICE, Action.of("can_read"), "account");
    private static final ActionSearchRequest ACTIONS = ActionSearchRequest.of(ALICE, ACCOUNT);

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

    private void publish(String searchEndpoints) {
        pdp.respondToMetadata(json(200, "{\"policy_decision_point\": \"" + pdp.url() + "\", "
                + "\"access_evaluation_endpoint\": \"" + pdp.url() + "/e\"" + searchEndpoints + "}"));
    }

    private RecordedRequest onlyRequest() {
        List<RecordedRequest> requests = pdp.requests();
        assertEquals(1, requests.size());
        return requests.get(0);
    }

    @Test
    void searchesSubjectsAtTheDefaultPathWithoutMetadata() {
        pdp.respond(json(200, "{\"results\": [{\"type\": \"user\", \"id\": \"alice\"}, {\"type\": \"user\", \"id\": \"bob\"}]}"));

        SearchResponse<Subject> response = builder().build().searchSubjects(SUBJECTS);

        assertEquals(List.of(ALICE, Subject.of("user", "bob")), response.getResults());
        RecordedRequest request = onlyRequest();
        assertEquals("POST", request.method());
        assertEquals("/access/v1/search/subject", request.path());
        assertEquals("Bearer " + TOKEN, request.header("Authorization"));
        assertEquals(Map.of(
                "subject", Map.of("type", "user"),
                "action", Map.of("name", "can_read"),
                "resource", Map.of("type", "account", "id", "123")),
                JsonCodec.defaultCodec().read(request.body()));
    }

    @Test
    void searchesResourcesAndActionsAtTheirDefaultPaths() {
        pdp.respond(json(200, "{\"results\": [{\"type\": \"account\", \"id\": \"123\"}]}"));
        AuthZenClient client = builder().build();

        assertEquals(List.of(ACCOUNT), client.searchResources(RESOURCES).getResults());

        pdp.respond(json(200, "{\"results\": [{\"name\": \"can_read\"}]}"));
        assertEquals(List.of(Action.of("can_read")),
                client.searchActionsAsync(ACTIONS).toCompletableFuture().join().getResults());

        List<RecordedRequest> requests = pdp.requests();
        assertEquals("/access/v1/search/resource", requests.get(0).path());
        assertEquals("/access/v1/search/action", requests.get(1).path());
    }

    @Test
    void usesTheDiscoveredEndpoints() {
        publish(", \"search_subject_endpoint\": \"" + pdp.url() + "/s/subject\""
                + ", \"search_resource_endpoint\": \"" + pdp.url() + "/s/resource\""
                + ", \"search_action_endpoint\": \"" + pdp.url() + "/s/action\"");
        pdp.respond(json(200, "{\"results\": []}"));
        AuthZenClient client = builder().build();

        client.searchSubjects(SUBJECTS);
        client.searchResources(RESOURCES);
        client.searchActions(ACTIONS);

        assertEquals(List.of("/s/subject", "/s/resource", "/s/action"),
                pdp.requests().stream().map(RecordedRequest::path).toList());
    }

    @Test
    void failsWithoutContactingAPdpThatDoesNotOfferTheSearch() {
        publish(", \"search_resource_endpoint\": \"" + pdp.url() + "/s/resource\"");
        AuthZenClient client = builder().build();

        PdpProtocolException subjects = assertThrows(PdpProtocolException.class, () -> client.searchSubjects(SUBJECTS));
        CompletionException actions = assertThrows(CompletionException.class,
                () -> client.searchActionsAsync(ACTIONS).toCompletableFuture().join());

        assertTrue(subjects.getMessage().contains("does not offer the Subject Search API"), subjects.getMessage());
        assertTrue(subjects.getMessage().contains("search_subject_endpoint"), subjects.getMessage());
        assertTrue(actions.getCause() instanceof PdpProtocolException, String.valueOf(actions.getCause()));
        assertTrue(actions.getCause().getMessage().contains("does not offer the Action Search API"));
        assertTrue(pdp.requests().isEmpty());
    }

    @Test
    void usesTheConfiguredEndpointsWithoutDiscovery() {
        publish("");
        pdp.respond(json(200, "{\"results\": []}"));
        AuthZenClient client = builder()
                .searchSubjectEndpoint(URI.create(pdp.url() + "/custom/subject"))
                .searchResourceEndpoint(URI.create(pdp.url() + "/custom/resource"))
                .searchActionEndpoint(URI.create(pdp.url() + "/custom/action"))
                .build();

        client.searchSubjects(SUBJECTS);
        client.searchResources(RESOURCES);
        client.searchActions(ACTIONS);

        assertEquals(List.of("/custom/subject", "/custom/resource", "/custom/action"),
                pdp.requests().stream().map(RecordedRequest::path).toList());
        assertTrue(pdp.metadataRequests().isEmpty());
    }

    @Test
    void followsThePagesWithTheSameRequestAndTheNextToken() {
        ResourceSearchRequest first = ResourceSearchRequest.builder()
                .subject(ALICE).action(Action.of("can_read")).resourceType("account")
                .page(PageRequest.ofLimit(1))
                .build();
        pdp.respond(json(200, "{\"page\": {\"next_token\": \"t2\"}, \"results\": [{\"type\": \"account\", \"id\": \"1\"}]}"));
        AuthZenClient client = builder().build();

        SearchResponse<Resource> page1 = client.searchResources(first);
        pdp.respond(json(200, "{\"page\": {\"next_token\": \"\"}, \"results\": [{\"type\": \"account\", \"id\": \"2\"}]}"));
        SearchResponse<Resource> page2 = client.searchResources(first.withPageToken(page1.getNextPageToken().orElseThrow()));

        assertTrue(page2.getNextPageToken().isEmpty());
        List<RecordedRequest> requests = pdp.requests();
        Map<?, ?> second = (Map<?, ?>) JsonCodec.defaultCodec().read(requests.get(1).body());
        assertEquals(Map.of("token", "t2", "limit", 1L), second.get("page"));
        assertEquals(Map.of("type", "account"), second.get("resource"));
    }

    @Test
    void searchesAsynchronouslyAndSynchronously() {
        pdp.respond(json(200, "{\"results\": []}"));
        AuthZenClient client = builder().build();

        assertTrue(client.searchSubjectsAsync(SUBJECTS).toCompletableFuture().join().getResults().isEmpty());
        assertTrue(client.searchResourcesAsync(RESOURCES).toCompletableFuture().join().getResults().isEmpty());
        assertTrue(client.searchActions(ACTIONS).getResults().isEmpty());
        assertEquals(3, pdp.requests().size());
    }

    @Test
    void rejectsAnInvalidAnswer() {
        pdp.respond(json(200, "{\"results\": [{\"type\": \"user\"}]}"));

        assertThrows(PdpProtocolException.class, () -> builder().build().searchSubjects(SUBJECTS));
    }

    @Test
    void failsClosedWhenThePdpIsDown() {
        pdp.respond(json(503, ""));

        assertThrows(PdpCommunicationException.class, () -> builder().build().searchResources(RESOURCES));
    }

    @Test
    void sharesTheCircuitBreakerWithEvaluations() {
        pdp.respond(json(503, ""));
        AuthZenClient client = builder().build();
        for (int i = 0; i < CircuitBreakerOptions.DEFAULT_FAILURE_THRESHOLD; i++) {
            assertThrows(PdpCommunicationException.class, () -> client.searchResources(RESOURCES));
        }

        PdpCommunicationException e = assertThrows(PdpCommunicationException.class,
                () -> client.evaluate(EvaluationRequest.of(ALICE, Action.of("can_read"), ACCOUNT)));

        assertTrue(e.getMessage().startsWith("PDP authorization API is suspended"), e.getMessage());
        assertEquals(CircuitBreakerOptions.DEFAULT_FAILURE_THRESHOLD, pdp.requests().size());
    }

    @Test
    void warnsAboutPlainHttpOnAnOverriddenSearchEndpoint() {
        TestLoggerFinder.clearRecords();

        AuthZenClient.builder()
                .pdpUrl("https://pdp.example.com")
                .searchActionEndpoint(URI.create("http://search.example.com/action"))
                .credentials(CredentialProvider.staticBearer(TOKEN))
                .discovery(false)
                .build();

        assertTrue(TestLoggerFinder.records().stream().anyMatch(record -> record.startsWith("WARNING")
                        && record.contains("http://search.example.com/action uses plain HTTP")),
                String.valueOf(TestLoggerFinder.records()));
    }

    @Test
    void rejectsAnInvalidOverride() {
        AuthZenClient.Builder builder = builder().searchSubjectEndpoint(URI.create("ftp://pdp.example.com/s"));

        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
