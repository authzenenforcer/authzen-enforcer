package io.github.authzenenforcer.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpCommunicationException;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class EventBuildersTest {

    private static final URI ENDPOINT = URI.create("https://pdp.example.com/access/v1/evaluation");

    private static AttemptCompleted.Builder attempt() {
        return AttemptCompleted.builder()
                .operation(Operation.EVALUATION)
                .endpoint(ENDPOINT)
                .method("POST")
                .requestId("r1")
                .attempt(1)
                .startTime(Instant.EPOCH)
                .duration(Duration.ofMillis(5));
    }

    @Test
    void buildsAnAttempt() {
        AttemptCompleted retried = attempt().status(503).retry(Duration.ofMillis(200)).build();

        assertEquals(503, retried.getStatus().getAsInt());
        assertEquals(AttemptCompleted.Next.RETRY, retried.getNext());
        assertEquals(Duration.ofMillis(200), retried.getRetryDelay().orElseThrow());

        AttemptCompleted failed = attempt().failure(new IOException("refused")).build();
        assertTrue(failed.getStatus().isEmpty());
        assertEquals(AttemptCompleted.Next.NONE, failed.getNext());
        assertTrue(failed.toString().contains("IOException"));
    }

    @Test
    void requiresExactlyOneOfStatusAndFailure() {
        assertThrows(IllegalStateException.class, () -> attempt().build());
        assertThrows(IllegalStateException.class, () -> attempt().status(200).failure(new IOException()).build());
        assertThrows(IllegalStateException.class, () -> attempt().status(200).attempt(0).build());
    }

    @Test
    void buildsABreakerTransition() {
        BreakerTransition opened = BreakerTransition.builder()
                .breaker(BreakerTransition.Breaker.METADATA_DISCOVERY)
                .from(BreakerTransition.State.HALF_OPEN)
                .to(BreakerTransition.State.OPEN)
                .openDuration(Duration.ofSeconds(10))
                .time(Instant.EPOCH)
                .build();

        assertEquals(Duration.ofSeconds(10), opened.getOpenDuration().orElseThrow());
        assertThrows(IllegalStateException.class, () -> BreakerTransition.builder()
                .breaker(BreakerTransition.Breaker.METADATA_DISCOVERY)
                .from(BreakerTransition.State.CLOSED)
                .to(BreakerTransition.State.OPEN)
                .time(Instant.EPOCH)
                .build());
    }

    @Test
    void matchesMetadataAndFailureToTheResult() {
        URI location = URI.create("https://pdp.example.com/.well-known/authzen-configuration");
        MetadataRefresh stale = MetadataRefresh.builder()
                .result(MetadataRefresh.Result.STALE)
                .location(location)
                .failure(new PdpCommunicationException("unreachable"))
                .time(Instant.EPOCH)
                .build();
        assertTrue(stale.getMetadata().isEmpty());

        assertThrows(IllegalStateException.class, () -> MetadataRefresh.builder()
                .result(MetadataRefresh.Result.FRESH).location(location).time(Instant.EPOCH).build());
        assertThrows(IllegalStateException.class, () -> MetadataRefresh.builder()
                .result(MetadataRefresh.Result.FAILED).location(location).time(Instant.EPOCH).build());
        assertThrows(IllegalStateException.class, () -> MetadataRefresh.builder()
                .result(MetadataRefresh.Result.DEFAULT_PATHS).location(location)
                .failure(new PdpCommunicationException("unreachable")).time(Instant.EPOCH).build());
    }
}
