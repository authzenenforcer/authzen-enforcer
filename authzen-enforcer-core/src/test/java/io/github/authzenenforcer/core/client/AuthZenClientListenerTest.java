package io.github.authzenenforcer.core.client;

import static io.github.authzenenforcer.core.client.FakePdp.json;
import static io.github.authzenenforcer.core.client.FakePdp.sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.event.AttemptCompleted;
import io.github.authzenenforcer.core.event.BreakerTransition;
import io.github.authzenenforcer.core.event.CallCompleted;
import io.github.authzenenforcer.core.event.EventContent;
import io.github.authzenenforcer.core.event.MetadataRefresh;
import io.github.authzenenforcer.core.event.Operation;
import io.github.authzenenforcer.core.event.Outcome;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.SearchResponse;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.model.SubjectSearchRequest;
import io.github.authzenenforcer.core.spi.PepListener;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthZenClientListenerTest {

    private static final EvaluationRequest REQUEST = EvaluationRequest.builder()
            .subject(Subject.builder().type("user").id("alice@example.com").property("department", "sales").build())
            .action(Action.of("can_read"))
            .resource(Resource.of("account", "123"))
            .context(Context.builder().put("ip", "10.0.0.1").build())
            .build();

    private FakePdp pdp;
    private final Recorder recorder = new Recorder();

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
                .listener(recorder);
    }

    /** Records every event on the listener thread. */
    private static final class Recorder implements PepListener {

        final List<Object> events = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();

        @Override
        public void onCallCompleted(CallCompleted<?, ?> event) {
            record(event);
        }

        @Override
        public void onAttemptCompleted(AttemptCompleted event) {
            record(event);
        }

        @Override
        public void onBreakerTransition(BreakerTransition event) {
            record(event);
        }

        @Override
        public void onMetadataRefresh(MetadataRefresh event) {
            record(event);
        }

        private void record(Object event) {
            threads.add(Thread.currentThread().getName());
            events.add(event);
        }

        <T> List<T> of(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }

        List<Object> await(int count) {
            awaitCondition(() -> events.size() >= count);
            return List.copyOf(events);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("condition not met in time");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static CallCompleted<?, ?> call(Object event) {
        return assertInstanceOf(CallCompleted.class, event);
    }

    private static CallCompleted<EvaluationRequest, EvaluationResponse> evaluation(Object event) {
        return call(event).as(Operation.EVALUATION).orElseThrow();
    }

    @Test
    void reportsTheMetadataRequestTheExchangeAndThenTheCall() {
        AuthZenClient client = builder().build();

        assertTrue(client.evaluate(REQUEST).getDecision());

        List<Object> events = recorder.await(4);
        assertEquals(4, events.size());
        AttemptCompleted metadataAttempt = assertInstanceOf(AttemptCompleted.class, events.get(0));
        assertEquals(Operation.METADATA, metadataAttempt.getOperation());
        assertEquals("GET", metadataAttempt.getMethod());
        assertEquals(404, metadataAttempt.getStatus().getAsInt());
        MetadataRefresh refresh = assertInstanceOf(MetadataRefresh.class, events.get(1));
        assertEquals(MetadataRefresh.Result.DEFAULT_PATHS, refresh.getResult());

        AttemptCompleted attempt = assertInstanceOf(AttemptCompleted.class, events.get(2));
        assertEquals(Operation.EVALUATION, attempt.getOperation());
        assertEquals("POST", attempt.getMethod());
        assertEquals(1, attempt.getAttempt());
        assertEquals(200, attempt.getStatus().getAsInt());
        assertEquals(AttemptCompleted.Next.NONE, attempt.getNext());
        assertEquals(pdp.requests().get(0).header("X-Request-ID"), attempt.getRequestId());
        assertEquals(pdp.url().resolve("/access/v1/evaluation"), attempt.getEndpoint());

        CallCompleted<EvaluationRequest, EvaluationResponse> call = evaluation(events.get(3));
        assertEquals(Outcome.PERMIT, call.getOutcome());
        assertEquals(List.of(attempt.getRequestId()), call.getRequestIds());
        assertEquals(attempt.getEndpoint(), call.getEndpoint().orElseThrow());
        assertTrue(call.getResponse().orElseThrow().getDecision());
        assertTrue(call.getFailure().isEmpty());
    }

    @Test
    void deliversEventsOnTheListenerThread() {
        AuthZenClient client = builder().discovery(false).build();

        client.evaluate(REQUEST);

        recorder.await(2);
        assertTrue(recorder.threads.stream().allMatch("authzen-enforcer-events"::equals), recorder.threads::toString);
    }

    @Test
    void masksPersonalDataByDefault() {
        pdp.respond(json(200, "{\"decision\": false, \"context\": {\"reason\": \"outside office hours\"}}"));
        AuthZenClient client = builder().discovery(false).build();

        client.evaluate(REQUEST);

        CallCompleted<EvaluationRequest, EvaluationResponse> call = evaluation(recorder.await(2).get(1));
        assertEquals(Outcome.DENY, call.getOutcome());
        EvaluationRequest request = call.getRequest();
        assertEquals(EventContent.MASK, request.getSubject().getId());
        assertEquals(Map.of("department", EventContent.MASK), request.getSubject().getProperties());
        assertEquals(Map.of("ip", EventContent.MASK), request.getContext().orElseThrow().asMap());
        assertEquals("123", request.getResource().getId());
        assertEquals("can_read", request.getAction().getName());
        assertEquals(Map.of("reason", EventContent.MASK),
                call.getResponse().orElseThrow().getContext().orElseThrow().asMap());
    }

    @Test
    void showsWhatTheEventContentIncludes() {
        AuthZenClient client = builder().discovery(false)
                .eventContent(EventContent.defaults().include("subject.id").exclude("resource.id"))
                .build();

        client.evaluate(REQUEST);

        EvaluationRequest request = evaluation(recorder.await(2).get(1)).getRequest();
        assertEquals("alice@example.com", request.getSubject().getId());
        assertEquals(EventContent.MASK, request.getResource().getId());
    }

    @Test
    void reportsEveryRetry() {
        pdp.respond(sequence(json(503, ""), json(200, "{\"decision\": true}")));
        AuthZenClient client = builder().discovery(false).build();

        client.evaluate(REQUEST);

        List<Object> events = recorder.await(3);
        AttemptCompleted first = assertInstanceOf(AttemptCompleted.class, events.get(0));
        AttemptCompleted second = assertInstanceOf(AttemptCompleted.class, events.get(1));
        assertEquals(503, first.getStatus().getAsInt());
        assertEquals(AttemptCompleted.Next.RETRY, first.getNext());
        assertTrue(first.getRetryDelay().isPresent());
        assertEquals(2, second.getAttempt());
        assertEquals(AttemptCompleted.Next.NONE, second.getNext());
        assertNotEquals(first.getRequestId(), second.getRequestId());
        CallCompleted<EvaluationRequest, EvaluationResponse> call = evaluation(events.get(2));
        assertEquals(List.of(first.getRequestId(), second.getRequestId()), call.getRequestIds());
        assertEquals(2, call.getAttempts());
    }

    @Test
    void reportsTheRepetitionAfterUnauthorized() {
        pdp.respond(sequence(json(401, ""), json(200, "{\"decision\": true}")));
        CredentialProvider credentials = new CredentialProvider() {
            @Override
            public AccessCredential getCredential() {
                return AccessCredential.bearer("token-1");
            }
        };
        AuthZenClient client = builder().discovery(false).credentials(credentials).build();

        client.evaluate(REQUEST);

        List<Object> events = recorder.await(3);
        AttemptCompleted first = assertInstanceOf(AttemptCompleted.class, events.get(0));
        assertEquals(401, first.getStatus().getAsInt());
        assertEquals(AttemptCompleted.Next.REFRESH_CREDENTIAL, first.getNext());
        assertEquals(Outcome.PERMIT, evaluation(events.get(2)).getOutcome());
    }

    @Test
    void reportsAFailedExchangeAndAFailedCall() {
        AuthZenClient client = builder().discovery(false).maxRetries(0).build();
        pdp.close();

        assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));

        List<Object> events = recorder.await(2);
        AttemptCompleted attempt = assertInstanceOf(AttemptCompleted.class, events.get(0));
        assertTrue(attempt.getStatus().isEmpty());
        assertInstanceOf(IOException.class, attempt.getFailure().orElseThrow());
        CallCompleted<EvaluationRequest, EvaluationResponse> call = evaluation(events.get(1));
        assertEquals(Outcome.FAILED, call.getOutcome());
        assertInstanceOf(PdpCommunicationException.class, call.getFailure().orElseThrow());
        assertTrue(call.getResponse().isEmpty());
    }

    @Test
    void reportsTheBreakerAndTheDenyThePdpNeverSaw() {
        pdp.respond(json(500, ""));
        AuthZenClient client = builder().discovery(false).maxRetries(0)
                .circuitBreaker(CircuitBreakerOptions.builder().failureThreshold(1).build())
                .build();

        assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));
        assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));

        recorder.await(4);
        BreakerTransition transition = recorder.of(BreakerTransition.class).get(0);
        assertEquals(BreakerTransition.Breaker.AUTHORIZATION_API, transition.getBreaker());
        assertEquals(BreakerTransition.State.CLOSED, transition.getFrom());
        assertEquals(BreakerTransition.State.OPEN, transition.getTo());
        assertTrue(transition.getOpenDuration().isPresent());
        List<CallCompleted> calls = recorder.of(CallCompleted.class);
        assertEquals(2, calls.size());
        assertEquals(1, calls.get(0).getAttempts());
        assertEquals(0, calls.get(1).getAttempts());
        assertEquals(Outcome.FAILED, calls.get(1).getOutcome());
        assertEquals(1, pdp.requests().size());
    }

    @Test
    void reportsFreshMetadata() {
        pdp.respondToMetadata(json(200, "{\"policy_decision_point\": \"" + pdp.url() + "\", "
                + "\"access_evaluation_endpoint\": \"" + pdp.url() + "/authzen/evaluation\"}"));
        AuthZenClient client = builder().build();

        client.evaluate(REQUEST);

        recorder.await(4);
        MetadataRefresh refresh = recorder.of(MetadataRefresh.class).get(0);
        assertEquals(MetadataRefresh.Result.FRESH, refresh.getResult());
        assertEquals(pdp.url() + "/authzen/evaluation",
                refresh.getMetadata().orElseThrow().getAccessEvaluationEndpoint().toString());
    }

    @Test
    void reportsFailedDiscoveryAndACallWithoutEndpoint() {
        pdp.respondToMetadata(json(400, ""));
        AuthZenClient client = builder().build();

        assertThrows(RuntimeException.class, () -> client.evaluate(REQUEST));

        recorder.await(3);
        MetadataRefresh refresh = recorder.of(MetadataRefresh.class).get(0);
        assertEquals(MetadataRefresh.Result.FAILED, refresh.getResult());
        assertTrue(refresh.getFailure().isPresent());
        CallCompleted<?, ?> call = recorder.of(CallCompleted.class).get(0);
        assertEquals(Outcome.FAILED, call.getOutcome());
        assertTrue(call.getEndpoint().isEmpty());
        assertEquals(0, call.getAttempts());
    }

    @Test
    void reportsSearchPagesWithMaskedResults() {
        pdp.respond(json(200, "{\"results\": [{\"type\": \"user\", \"id\": \"alice\"}]}"));
        AuthZenClient client = builder().discovery(false).build();

        client.searchSubjects(SubjectSearchRequest.of("user", Action.of("can_read"), Resource.of("account", "123")));

        CallCompleted<SubjectSearchRequest, SearchResponse<Subject>> call = call(recorder.await(2).get(1))
                .as(Operation.SUBJECT_SEARCH).orElseThrow();
        assertEquals(Outcome.COMPLETED, call.getOutcome());
        assertEquals(EventContent.MASK, call.getResponse().orElseThrow().getResults().get(0).getId());
        assertFalse(call.as(Operation.EVALUATION).isPresent());
    }

    @Test
    void aBlockedListenerDelaysNoDecision() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        PepListener blocked = new PepListener() {
            @Override
            public void onCallCompleted(CallCompleted<?, ?> event) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        AuthZenClient client = builder().discovery(false).listener(blocked).build();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 3; i++) {
                assertTrue(client.evaluate(REQUEST).getDecision());
            }
        });
        release.countDown();
        // The recorder has its own queue, so the blocked listener did not hold up its events
        recorder.await(6);
    }

    @Test
    void aFailingListenerChangesNoDecisionAndGetsLaterEvents() {
        List<Object> seen = new CopyOnWriteArrayList<>();
        PepListener failing = new PepListener() {
            @Override
            public void onCallCompleted(CallCompleted<?, ?> event) {
                seen.add(event);
                throw new IllegalStateException("listener failure");
            }
        };
        AuthZenClient client = builder().discovery(false).listener(failing).build();

        assertTrue(client.evaluate(REQUEST).getDecision());
        assertTrue(client.evaluate(REQUEST).getDecision());

        awaitCondition(() -> seen.size() == 2);
    }
}
