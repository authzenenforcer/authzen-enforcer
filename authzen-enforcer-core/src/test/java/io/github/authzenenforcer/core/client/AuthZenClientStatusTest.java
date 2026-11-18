package io.github.authzenenforcer.core.client;

import static io.github.authzenenforcer.core.client.FakePdp.json;
import static io.github.authzenenforcer.core.client.FakePdp.sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.event.BreakerTransition;
import io.github.authzenenforcer.core.event.MetadataRefresh;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthZenClientStatusTest {

    private static final EvaluationRequest REQUEST = EvaluationRequest.of(
            Subject.of("user", "alice@example.com"), Action.of("can_read"), Resource.of("account", "123"));

    private FakePdp pdp;
    private final FakeClock clock = new FakeClock();

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
                .requestTimeout(Duration.ofSeconds(2))
                .maxRetries(0)
                .clock(clock);
    }

    private String metadata() {
        return "{\"policy_decision_point\": \"" + pdp.url() + "\", "
                + "\"access_evaluation_endpoint\": \"" + pdp.url() + "/access/v1/evaluation\"}";
    }

    private static ClientStatus status(AuthZenClient client) {
        return client.status().orElseThrow();
    }

    private static MetadataRefresh lastRefresh(AuthZenClient client) {
        return status(client).getLastMetadataRefresh().orElseThrow();
    }

    @Test
    void startsClosedWithoutARefreshAndWithoutContactingThePdp() {
        ClientStatus status = status(builder().build());

        assertEquals(BreakerTransition.State.CLOSED, status.getAuthorizationApiBreaker());
        assertEquals(Optional.of(BreakerTransition.State.CLOSED), status.getMetadataDiscoveryBreaker());
        assertEquals(Optional.empty(), status.getLastMetadataRefresh());
        assertTrue(pdp.metadataRequests().isEmpty());
        assertTrue(pdp.requests().isEmpty());
    }

    @Test
    void reportsNoMetadataStateWithoutDiscovery() {
        AuthZenClient client = builder().discovery(false).build();

        client.evaluate(REQUEST);

        ClientStatus status = status(client);
        assertEquals(Optional.empty(), status.getMetadataDiscoveryBreaker());
        assertEquals(Optional.empty(), status.getLastMetadataRefresh());
    }

    @Test
    void reportsTheLastRefresh() {
        pdp.respondToMetadata(sequence(json(503, ""), json(200, metadata()), json(503, "")));
        AuthZenClient client = builder().build();

        assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));
        MetadataRefresh failed = lastRefresh(client);
        assertEquals(MetadataRefresh.Result.FAILED, failed.getResult());
        assertInstanceOf(PdpCommunicationException.class, failed.getFailure().orElseThrow());

        client.evaluate(REQUEST);
        assertEquals(MetadataRefresh.Result.FRESH, lastRefresh(client).getResult());

        clock.advance(DiscoveryOptions.DEFAULT_TTL);
        client.evaluate(REQUEST);
        MetadataRefresh stale = lastRefresh(client);
        assertEquals(MetadataRefresh.Result.STALE, stale.getResult());
        assertTrue(stale.getMetadata().isPresent());
        assertFalse(stale.getTime().isBefore(failed.getTime()));
    }

    @Test
    void reportsTheDefaultPathsOfAPdpWithoutMetadata() {
        AuthZenClient client = builder().build();

        client.evaluate(REQUEST);

        assertEquals(MetadataRefresh.Result.DEFAULT_PATHS, lastRefresh(client).getResult());
    }

    @Test
    void reportsAnOpenBreakerAsHalfOpenOnceTheNextCallWouldProbe() {
        pdp.respond(json(503, ""));
        AuthZenClient client = builder().discovery(false)
                .circuitBreaker(CircuitBreakerOptions.builder().failureThreshold(2).build())
                .build();

        assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));
        assertEquals(BreakerTransition.State.CLOSED, status(client).getAuthorizationApiBreaker());
        assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));
        assertEquals(BreakerTransition.State.OPEN, status(client).getAuthorizationApiBreaker());

        clock.advance(CircuitBreakerOptions.DEFAULT_MAX_OPEN_DURATION);
        assertEquals(BreakerTransition.State.HALF_OPEN, status(client).getAuthorizationApiBreaker());
        assertEquals(2, pdp.requests().size(), "taking the status does not probe");
    }

    @Test
    void reportsTheBreakerOfTheMetadataRequest() {
        pdp.respondToMetadata(json(503, ""));
        AuthZenClient client = builder()
                .circuitBreaker(CircuitBreakerOptions.builder().failureThreshold(1).build())
                .build();

        assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));

        ClientStatus status = status(client);
        assertEquals(Optional.of(BreakerTransition.State.OPEN), status.getMetadataDiscoveryBreaker());
        assertEquals(BreakerTransition.State.CLOSED, status.getAuthorizationApiBreaker());
    }

    @Test
    void reportsNothingForClientsThatDoNotTrackIt() {
        AuthZenClient client = (AuthZenClient) Proxy.newProxyInstance(AuthZenClient.class.getClassLoader(),
                new Class<?>[] {AuthZenClient.class}, InvocationHandler::invokeDefault);

        assertEquals(Optional.empty(), client.status());
    }
}
