package io.github.authzenenforcer.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.event.AttemptCompleted;
import io.github.authzenenforcer.core.event.BreakerTransition;
import io.github.authzenenforcer.core.event.CallCompleted;
import io.github.authzenenforcer.core.event.MetadataRefresh;
import io.github.authzenenforcer.core.event.Operation;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationItem;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class MicrometerPepListenerTest {

    private static final URI ENDPOINT = URI.create("https://pdp.example.com/access/v1/evaluation");
    private static final EvaluationRequest REQUEST = EvaluationRequest.of(Subject.of("user", "alice@example.com"),
            Action.of("can_read"), Resource.of("account", "123"));

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static CallCompleted<EvaluationRequest, EvaluationResponse> evaluation(boolean decision) {
        return CallCompleted.builder(Operation.EVALUATION)
                .request(REQUEST)
                .response(EvaluationResponse.of(decision))
                .requestId("r1")
                .startTime(Instant.EPOCH)
                .duration(Duration.ofMillis(12))
                .build();
    }

    private static CallCompleted<EvaluationsRequest, EvaluationsResponse> failedBatch() {
        return CallCompleted.builder(Operation.EVALUATIONS)
                .request(EvaluationsRequest.builder()
                        .evaluation(EvaluationItem.of(REQUEST.getSubject(), REQUEST.getAction(), REQUEST.getResource()))
                        .build())
                .failure(new PdpCommunicationException("PDP unreachable", new IOException("refused")))
                .startTime(Instant.EPOCH)
                .duration(Duration.ofMillis(5))
                .build();
    }

    private static BreakerTransition transition(BreakerTransition.State from, BreakerTransition.State to) {
        BreakerTransition.Builder builder = BreakerTransition.builder()
                .breaker(BreakerTransition.Breaker.AUTHORIZATION_API)
                .from(from)
                .to(to)
                .time(Instant.EPOCH);
        if (to == BreakerTransition.State.OPEN) {
            builder.openDuration(Duration.ofSeconds(5));
        }
        return builder.build();
    }

    @Test
    void timesCallsByOperationOutcomeAndException() {
        MicrometerPepListener listener = MicrometerPepListener.builder(registry).build();

        listener.onCallCompleted(evaluation(true));
        listener.onCallCompleted(evaluation(true));
        listener.onCallCompleted(evaluation(false));
        listener.onCallCompleted(failedBatch());

        Timer permits = registry.get(MicrometerPepListener.CALLS)
                .tags("operation", "evaluation", "outcome", "permit", "exception", "none").timer();
        assertEquals(2, permits.count());
        assertEquals(24, permits.totalTime(TimeUnit.MILLISECONDS), 0.001);
        assertEquals(1, registry.get(MicrometerPepListener.CALLS).tags("outcome", "deny").timer().count());
        assertEquals(1, registry.get(MicrometerPepListener.CALLS)
                .tags("operation", "evaluations", "outcome", "failed", "exception", "PdpCommunicationException")
                .timer().count());
    }

    @Test
    void neverTagsIdentifiers() {
        MicrometerPepListener listener = MicrometerPepListener.builder(registry)
                .resourceTypeTag(true)
                .actionNameTag(true)
                .build();

        listener.onCallCompleted(evaluation(true));

        for (Meter meter : registry.getMeters()) {
            meter.getId().getTags().forEach(tag -> {
                assertNotEquals("alice@example.com", tag.getValue());
                assertNotEquals("123", tag.getValue());
                assertNotEquals("r1", tag.getValue());
            });
        }
    }

    @Test
    void addsResourceTypeAndActionNameOnlyWhenAskedTo() {
        MicrometerPepListener plain = MicrometerPepListener.builder(registry).build();
        plain.onCallCompleted(evaluation(true));
        assertNull(registry.get(MicrometerPepListener.CALLS).timer().getId().getTag("resource.type"));

        SimpleMeterRegistry tagged = new SimpleMeterRegistry();
        MicrometerPepListener listener = MicrometerPepListener.builder(tagged)
                .resourceTypeTag(true)
                .actionNameTag(true)
                .build();
        listener.onCallCompleted(evaluation(true));
        listener.onCallCompleted(failedBatch());

        assertEquals(1, tagged.get(MicrometerPepListener.CALLS)
                .tags("operation", "evaluation", "resource.type", "account", "action.name", "can_read")
                .timer().count());
        // Other operations keep the same tag keys
        assertEquals(1, tagged.get(MicrometerPepListener.CALLS)
                .tags("operation", "evaluations", "resource.type", "none", "action.name", "none")
                .timer().count());
    }

    @Test
    void timesAttemptsByStatusExceptionAndNextStep() {
        MicrometerPepListener listener = MicrometerPepListener.builder(registry).build();
        AttemptCompleted.Builder attempt = AttemptCompleted.builder()
                .operation(Operation.EVALUATION)
                .endpoint(ENDPOINT)
                .method("POST")
                .requestId("r1")
                .attempt(1)
                .startTime(Instant.EPOCH)
                .duration(Duration.ofMillis(3));

        listener.onAttemptCompleted(attempt.status(503).retry(Duration.ofMillis(100)).build());
        listener.onAttemptCompleted(AttemptCompleted.builder()
                .operation(Operation.METADATA)
                .endpoint(ENDPOINT)
                .method("GET")
                .requestId("r2")
                .attempt(2)
                .failure(new IOException("reset"))
                .startTime(Instant.EPOCH)
                .duration(Duration.ofMillis(3))
                .build());

        assertEquals(1, registry.get(MicrometerPepListener.ATTEMPTS)
                .tags("operation", "evaluation", "status", "503", "exception", "none", "next", "retry")
                .timer().count());
        assertEquals(1, registry.get(MicrometerPepListener.ATTEMPTS)
                .tags("operation", "metadata", "status", "none", "exception", "IOException", "next", "none")
                .timer().count());
    }

    @Test
    void countsTransitionsAndTracksTheBreakerState() {
        MicrometerPepListener listener = MicrometerPepListener.builder(registry).build();
        assertEquals(0, state("authorization_api"));
        assertEquals(0, state("metadata_discovery"));

        listener.onBreakerTransition(transition(BreakerTransition.State.CLOSED, BreakerTransition.State.OPEN));
        assertEquals(2, state("authorization_api"));
        listener.onBreakerTransition(transition(BreakerTransition.State.OPEN, BreakerTransition.State.HALF_OPEN));
        assertEquals(1, state("authorization_api"));
        listener.onBreakerTransition(transition(BreakerTransition.State.HALF_OPEN, BreakerTransition.State.CLOSED));
        assertEquals(0, state("authorization_api"));

        assertEquals(1, registry.get(MicrometerPepListener.BREAKER_TRANSITIONS)
                .tags("breaker", "authorization_api", "from", "open", "to", "half_open").counter().count());
        assertEquals(3, registry.get(MicrometerPepListener.BREAKER_TRANSITIONS).counters().size());
    }

    private double state(String breaker) {
        return registry.get(MicrometerPepListener.BREAKER_STATE).tag("breaker", breaker).gauge().value();
    }

    @Test
    void countsMetadataRefreshesByResult() {
        MicrometerPepListener listener = MicrometerPepListener.builder(registry).build();
        URI location = URI.create("https://pdp.example.com/.well-known/authzen-configuration");

        listener.onMetadataRefresh(MetadataRefresh.builder()
                .result(MetadataRefresh.Result.DEFAULT_PATHS)
                .location(location)
                .time(Instant.EPOCH)
                .build());

        assertEquals(1, registry.get(MicrometerPepListener.METADATA_REFRESHES)
                .tag("result", "default_paths").counter().count());
    }

    @Test
    void addsCommonTagsToEveryMeter() {
        MicrometerPepListener listener = MicrometerPepListener.builder(registry)
                .tags(Tags.of("pdp", "main"))
                .tag("region", "eu")
                .build();
        listener.onCallCompleted(evaluation(true));
        listener.onBreakerTransition(transition(BreakerTransition.State.CLOSED, BreakerTransition.State.OPEN));

        List<Meter> meters = registry.getMeters();
        assertEquals(4, meters.size());
        for (Meter meter : meters) {
            assertEquals("main", meter.getId().getTag("pdp"), meter.getId()::toString);
            assertEquals("eu", meter.getId().getTag("region"), meter.getId()::toString);
        }
    }

    @Test
    void namesAnonymousExceptionsByTheirClass() {
        MicrometerPepListener listener = MicrometerPepListener.builder(registry).build();
        AuthZenException anonymous = new AuthZenException("failed") {
        };
        listener.onCallCompleted(CallCompleted.builder(Operation.EVALUATION)
                .request(REQUEST)
                .failure(anonymous)
                .startTime(Instant.EPOCH)
                .duration(Duration.ZERO)
                .build());

        assertNotNull(registry.get(MicrometerPepListener.CALLS)
                .tag("exception", anonymous.getClass().getName()).timer());
    }
}
