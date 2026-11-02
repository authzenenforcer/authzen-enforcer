package io.github.authzenenforcer.core.client;

import static io.github.authzenenforcer.core.client.FakePdp.json;
import static io.github.authzenenforcer.core.client.FakePdp.sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpAuthenticationException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.FakePdp.RecordedRequest;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthZenClientDiscoveryTest {

    private static final String TOKEN = "token-1";
    private static final EvaluationRequest REQUEST = EvaluationRequest.of(
            Subject.of("user", "alice@example.com"), Action.of("can_read"), Resource.of("account", "123"));

    private FakePdp pdp;

    @BeforeEach
    void startPdp() throws IOException {
        pdp = FakePdp.start();
    }

    @AfterEach
    void stopPdp() {
        pdp.close();
    }

    private AuthZenClient.Builder builder(String path) {
        return AuthZenClient.builder()
                .pdpUrl(pdp.url() + path)
                .credentials(CredentialProvider.staticBearer(TOKEN))
                .requestTimeout(Duration.ofSeconds(2))
                .maxRetries(0);
    }

    private AuthZenClient client() {
        return builder("").build();
    }

    private String metadata(String identifier, String evaluationPath) {
        return "{\"policy_decision_point\": \"" + identifier + "\", "
                + "\"access_evaluation_endpoint\": \"" + pdp.url() + evaluationPath + "\"}";
    }

    private void publish(String evaluationPath) {
        pdp.respondToMetadata(json(200, metadata(pdp.url().toString(), evaluationPath)));
    }

    private List<String> paths() {
        return pdp.requests().stream().map(RecordedRequest::path).toList();
    }

    @Test
    void sendsEvaluationsToTheDiscoveredEndpoint() {
        publish("/authzen/access/v1/evaluation");

        assertTrue(client().evaluate(REQUEST).getDecision());

        assertEquals(List.of("/authzen/access/v1/evaluation"), paths());
    }

    @Test
    void getsTheMetadataWithoutCredentials() {
        publish("/authzen/access/v1/evaluation");

        client().evaluate(REQUEST);

        RecordedRequest request = pdp.metadataRequests().get(0);
        assertEquals("GET", request.method());
        assertEquals(FakePdp.WELL_KNOWN_PATH, request.path());
        assertEquals("application/json", request.header("Accept"));
        assertTrue(request.header("User-Agent").startsWith("authzen-enforcer"));
        assertTrue(request.header("X-Request-ID") != null);
        assertNull(request.header("Authorization"));
        assertEquals("Bearer " + TOKEN, pdp.requests().get(0).header("Authorization"));
    }

    @Test
    void insertsTheWellKnownPathBeforeThePathOfThePdpUrl() {
        pdp.respondToMetadata(json(200, metadata(pdp.url() + "/realms/demo", "/realms/demo/authzen/access/v1/evaluation")));

        builder("/realms/demo/").build().evaluate(REQUEST);

        assertEquals(FakePdp.WELL_KNOWN_PATH + "/realms/demo", pdp.metadataRequests().get(0).path());
        assertEquals(List.of("/realms/demo/authzen/access/v1/evaluation"), paths());
    }

    @Test
    void fetchesTheMetadataOnceForManyEvaluations() {
        publish("/authzen/access/v1/evaluation");
        AuthZenClient client = client();

        for (int i = 0; i < 3; i++) {
            client.evaluate(REQUEST);
        }

        assertEquals(1, pdp.metadataRequests().size());
        assertEquals(3, pdp.requests().size());
    }

    @Test
    void sharesOneMetadataRequestBetweenConcurrentEvaluations() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        pdp.respondToMetadata((request, exchange) -> {
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            json(200, metadata(pdp.url().toString(), "/authzen/access/v1/evaluation")).respond(request, exchange);
        });
        AuthZenClient client = client();

        List<CompletableFuture<EvaluationResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(client.evaluateAsync(REQUEST));
        }
        release.countDown();
        futures.forEach(CompletableFuture::join);

        assertEquals(1, pdp.metadataRequests().size());
        assertEquals(20, pdp.requests().size());
    }

    @Test
    void cancellingOneCallerDoesNotFailTheOthers() {
        CountDownLatch release = new CountDownLatch(1);
        pdp.respondToMetadata((request, exchange) -> {
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            json(200, metadata(pdp.url().toString(), "/authzen/access/v1/evaluation")).respond(request, exchange);
        });
        AuthZenClient client = client();

        CompletableFuture<Optional<PdpMetadata>> cancelled = client.metadataAsync();
        CompletableFuture<Optional<PdpMetadata>> waiting = client.metadataAsync();
        cancelled.cancel(true);
        release.countDown();

        assertTrue(waiting.join().isPresent());
    }

    @Test
    void usesTheDefaultPathsWhenThePdpPublishesNoMetadata() {
        AuthZenClient client = client();

        client.evaluate(REQUEST);
        client.evaluate(REQUEST);

        assertEquals(List.of("/access/v1/evaluation", "/access/v1/evaluation"), paths());
        assertEquals(1, pdp.metadataRequests().size());
        assertEquals(Optional.empty(), client.metadata());
    }

    @Test
    void returnsTheDiscoveredMetadata() {
        pdp.respondToMetadata(json(200, "{\"policy_decision_point\": \"" + pdp.url() + "\", "
                + "\"access_evaluation_endpoint\": \"" + pdp.url() + "/e\", "
                + "\"search_subject_endpoint\": \"" + pdp.url() + "/s\", "
                + "\"capabilities\": [\"urn:x\"]}"));

        PdpMetadata metadata = client().metadata().orElseThrow();

        assertEquals(pdp.url().toString(), metadata.getPolicyDecisionPoint());
        assertEquals(URI.create(pdp.url() + "/e"), metadata.getAccessEvaluationEndpoint());
        assertEquals(Optional.of(URI.create(pdp.url() + "/s")), metadata.getSearchSubjectEndpoint());
        assertTrue(metadata.getAccessEvaluationsEndpoint().isEmpty());
        assertEquals(List.of("urn:x"), metadata.getCapabilities());
    }

    @Test
    void doesNotDiscoverWhenTheEvaluationEndpointIsSet() {
        publish("/authzen/access/v1/evaluation");

        builder("").evaluationEndpoint(URI.create(pdp.url() + "/custom")).build().evaluate(REQUEST);

        assertEquals(List.of("/custom"), paths());
        assertTrue(pdp.metadataRequests().isEmpty());
    }

    @Test
    void usesTheDefaultPathsWhenDiscoveryIsDisabled() {
        publish("/authzen/access/v1/evaluation");
        AuthZenClient client = builder("").discovery(false).build();

        client.evaluate(REQUEST);

        assertEquals(List.of("/access/v1/evaluation"), paths());
        assertEquals(Optional.empty(), client.metadata());
        assertTrue(pdp.metadataRequests().isEmpty());
    }

    @Test
    void doesNotContactThePdpWhenBuilt() {
        client();

        assertTrue(pdp.metadataRequests().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/other", "/"})
    void rejectsMetadataForAnotherPdpWithoutEvaluating(String suffix) {
        pdp.respondToMetadata(json(200, metadata(pdp.url() + suffix, "/authzen/access/v1/evaluation")));

        assertThrows(PdpProtocolException.class, () -> client().evaluate(REQUEST));
        assertTrue(pdp.requests().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[]", "{}", "{\"policy_decision_point\": \"https://pdp.example.com\"}"})
    void rejectsInvalidMetadata(String body) {
        pdp.respondToMetadata(json(200, body));

        assertThrows(PdpProtocolException.class, () -> client().evaluate(REQUEST));
        assertTrue(pdp.requests().isEmpty());
    }

    @Test
    void rejectsMetadataWithAnUnusableEndpoint() {
        pdp.respondToMetadata(json(200, "{\"policy_decision_point\": \"" + pdp.url() + "\", "
                + "\"access_evaluation_endpoint\": \"/access/v1/evaluation\"}"));

        assertThrows(PdpProtocolException.class, () -> client().evaluate(REQUEST));
        assertTrue(pdp.requests().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void failsClosedAndTriesAgainWhenTheMetadataCannotBeFetched(int status) {
        pdp.respondToMetadata(sequence(json(status, ""), json(200, metadata(pdp.url().toString(), "/authzen/access/v1/evaluation"))));
        AuthZenClient client = client();

        assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));
        assertTrue(client.evaluate(REQUEST).getDecision());

        assertEquals(2, pdp.metadataRequests().size());
        assertEquals(List.of("/authzen/access/v1/evaluation"), paths());
    }

    @Test
    void retriesTransientMetadataFailuresAsConfigured() {
        pdp.respondToMetadata(sequence(json(503, ""), json(200, metadata(pdp.url().toString(), "/authzen/access/v1/evaluation"))));

        assertTrue(builder("").maxRetries(1).build().evaluate(REQUEST).getDecision());

        assertEquals(2, pdp.metadataRequests().size());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void failsClosedWhenTheMetadataRequiresAuthentication(int status) {
        pdp.respondToMetadata(json(status, ""));

        PdpAuthenticationException e = assertThrows(PdpAuthenticationException.class, () -> client().evaluate(REQUEST));
        assertEquals(status, e.getStatusCode());
        assertTrue(pdp.requests().isEmpty());
    }

    @Test
    void failsClosedWhenThePdpIsUnreachable() {
        AuthZenClient client = AuthZenClient.builder()
                .pdpUrl("http://127.0.0.1:1")
                .maxRetries(0)
                .build();

        assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));
    }

    @Test
    void refreshesTheMetadataAfterTheInterval() throws InterruptedException {
        pdp.respondToMetadata(sequence(
                json(200, metadata(pdp.url().toString(), "/first")),
                json(200, metadata(pdp.url().toString(), "/second"))));
        AuthZenClient client = builder("").metadataRefreshInterval(Duration.ofMillis(50)).build();

        client.evaluate(REQUEST);
        Thread.sleep(100);
        client.evaluate(REQUEST);

        assertEquals(List.of("/first", "/second"), paths());
    }

    @Test
    void keepsThePreviousMetadataWhenTheRefreshCannotReachThePdp() throws InterruptedException {
        pdp.respondToMetadata(sequence(json(200, metadata(pdp.url().toString(), "/first")), json(503, "")));
        AuthZenClient client = builder("").metadataRefreshInterval(Duration.ofMillis(50)).build();

        client.evaluate(REQUEST);
        Thread.sleep(100);

        assertTrue(client.evaluate(REQUEST).getDecision());
        assertEquals(List.of("/first", "/first"), paths());
        assertEquals(2, pdp.metadataRequests().size());
    }

    @Test
    void failsClosedWhenTheRefreshReturnsMetadataForAnotherPdp() throws InterruptedException {
        pdp.respondToMetadata(sequence(
                json(200, metadata(pdp.url().toString(), "/first")),
                json(200, metadata("https://attacker.example.com", "/first"))));
        AuthZenClient client = builder("").metadataRefreshInterval(Duration.ofMillis(50)).build();

        client.evaluate(REQUEST);
        Thread.sleep(100);

        assertThrows(PdpProtocolException.class, () -> client.evaluate(REQUEST));
        assertEquals(List.of("/first"), paths());
    }
}
