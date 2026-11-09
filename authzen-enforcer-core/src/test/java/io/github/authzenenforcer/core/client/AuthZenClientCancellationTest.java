package io.github.authzenenforcer.core.client;

import static io.github.authzenenforcer.core.client.FakePdp.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.event.CallCompleted;
import io.github.authzenenforcer.core.event.Outcome;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.model.SubjectSearchRequest;
import io.github.authzenenforcer.core.spi.PepListener;
import io.smallrye.mutiny.Uni;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** A caller that gives up stops the call: nothing is sent to the PDP afterwards. */
class AuthZenClientCancellationTest {

    private static final EvaluationRequest REQUEST = EvaluationRequest.of(
            Subject.of("user", "alice@example.com"), Action.of("can_read"), Resource.of("account", "123"));
    private static final Duration CALLER_TIMEOUT = Duration.ofMillis(300);
    // Longer than the one-second Retry-After, so that a retry the cancel failed to stop would show
    private static final Duration AFTER_RETRY = Duration.ofMillis(1500);

    private FakePdp pdp;
    private final Calls calls = new Calls();

    @BeforeEach
    void startPdp() throws IOException {
        pdp = FakePdp.start();
    }

    @AfterEach
    void stopPdp() {
        pdp.close();
    }

    private AuthZenClient client() {
        return AuthZenClient.builder()
                .pdpUrl(pdp.url())
                .credentials(CredentialProvider.staticBearer("token-1"))
                .requestTimeout(Duration.ofSeconds(5))
                .listener(calls)
                .build();
    }

    /** A PDP that keeps answering 503 and asks for a retry after one second. */
    private void overloaded() {
        pdp.respond((request, exchange) -> {
            exchange.getResponseHeaders().set("Retry-After", "1");
            json(503, "").respond(request, exchange);
        });
    }

    /** A PDP that answers only after the given time. */
    private void slow(Duration delay) {
        pdp.respond((request, exchange) -> {
            sleep(delay);
            json(200, "{\"decision\": true}").respond(request, exchange);
        });
    }

    @Test
    void stopsRetryingOnceAMonoTimesOut() {
        overloaded();
        AuthZenClient client = client();

        long start = System.nanoTime();
        assertThrows(RuntimeException.class,
                () -> Mono.fromCompletionStage(client.evaluateAsync(REQUEST)).timeout(CALLER_TIMEOUT).block());

        assertTrue(elapsedSince(start).compareTo(Duration.ofSeconds(1)) < 0, "the caller gave up after 300 ms");
        assertNothingSentAfterTheCancel();
    }

    @Test
    void stopsRetryingOnceAMonoOverTheConvertedFutureTimesOut() {
        overloaded();
        AuthZenClient client = client();

        assertThrows(RuntimeException.class, () -> Mono.fromFuture(client.evaluateAsync(REQUEST).toCompletableFuture())
                .timeout(CALLER_TIMEOUT)
                .block());

        assertNothingSentAfterTheCancel();
    }

    @Test
    void stopsRetryingOnceAUniTimesOut() {
        overloaded();
        AuthZenClient client = client();

        assertThrows(RuntimeException.class, () -> Uni.createFrom().completionStage(client.evaluateAsync(REQUEST))
                .ifNoItem().after(CALLER_TIMEOUT).fail()
                .await().indefinitely());

        assertNothingSentAfterTheCancel();
    }

    @Test
    void stopsRetryingOnceTheStageIsCancelled() {
        overloaded();
        AuthZenClient client = client();
        CompletionStage<EvaluationResponse> stage = client.evaluateAsync(REQUEST);
        sleep(CALLER_TIMEOUT);

        assertTrue(stage.toCompletableFuture().cancel(false));

        assertNothingSentAfterTheCancel();
    }

    @Test
    void stopsRetryingOnceTheWaitingThreadIsInterrupted() throws InterruptedException {
        overloaded();
        AuthZenClient client = client();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                client.evaluate(REQUEST);
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        });
        caller.start();
        sleep(CALLER_TIMEOUT);

        caller.interrupt();
        caller.join(2000);

        assertInstanceOf(PdpCommunicationException.class, thrown.get());
        assertNothingSentAfterTheCancel();
    }

    @Test
    void abortsTheExchangeInFlight() {
        slow(Duration.ofSeconds(3));
        AuthZenClient client = client();
        CompletionStage<EvaluationResponse> stage = client.evaluateAsync(REQUEST);
        awaitCondition(() -> pdp.requests().size() == 1);
        long start = System.nanoTime();

        stage.toCompletableFuture().cancel(true);

        CallCompleted<?, ?> call = calls.awaitOne();
        assertEquals(Outcome.CANCELLED, call.getOutcome());
        assertTrue(elapsedSince(start).compareTo(Duration.ofSeconds(2)) < 0,
                "the call stopped without waiting for the PDP's answer");
    }

    @Test
    void sendsNoRequestWithACredentialThatArrivesAfterTheCancel() {
        CompletableFuture<AccessCredential> credential = new CompletableFuture<>();
        AuthZenClient client = AuthZenClient.builder()
                .pdpUrl(pdp.url())
                .credentials(CredentialProvider.async(() -> credential))
                .requestTimeout(Duration.ofSeconds(5))
                .listener(calls)
                .build();
        CompletionStage<EvaluationResponse> stage = client.evaluateAsync(REQUEST);

        stage.toCompletableFuture().cancel(true);
        credential.complete(AccessCredential.bearer("token-1"));
        sleep(Duration.ofMillis(300));

        assertTrue(pdp.requests().isEmpty());
        assertFalse(credential.isCancelled(), "the provider's stage may be shared, so it is left alone");
        assertEquals(Outcome.CANCELLED, calls.awaitOne().getOutcome());
    }

    @Test
    void cancelsThePageInFlightWhenTheSubscriberCancels() {
        slow(Duration.ofSeconds(3));
        AuthZenClient client = client();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        List<Object> signals = new CopyOnWriteArrayList<>();
        client.searchAllSubjectsAsync(SubjectSearchRequest.builder()
                .subjectType("user").action(Action.of("can_read")).resource(Resource.of("account", "123")).build())
                .subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        subscription.set(s);
                        s.request(1);
                    }

                    @Override
                    public void onNext(Subject item) {
                        signals.add(item);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        signals.add(throwable);
                    }

                    @Override
                    public void onComplete() {
                        signals.add("complete");
                    }
                });
        awaitCondition(() -> pdp.requests().size() == 1);
        long start = System.nanoTime();

        subscription.get().cancel();

        assertEquals(Outcome.CANCELLED, calls.awaitOne().getOutcome());
        assertTrue(elapsedSince(start).compareTo(Duration.ofSeconds(2)) < 0);
        assertTrue(signals.isEmpty(), "nothing is signalled after a cancel");
    }

    @Test
    void reportsTheCancelledCallWithItsExchanges() {
        overloaded();
        AuthZenClient client = client();
        CompletionStage<EvaluationResponse> stage = client.evaluateAsync(REQUEST);
        sleep(CALLER_TIMEOUT);

        stage.toCompletableFuture().cancel(true);

        CallCompleted<?, ?> call = calls.awaitOne();
        assertEquals(Outcome.CANCELLED, call.getOutcome());
        assertEquals(1, call.getAttempts());
        assertTrue(call.getFailure().orElseThrow().getMessage().contains("cancelled"));
        assertTrue(call.getResponse().isEmpty());
    }

    @Test
    void releasesTheBreakerPermitOfACancelledCall() {
        slow(Duration.ofSeconds(3));
        AuthZenClient client = AuthZenClient.builder()
                .pdpUrl(pdp.url())
                .requestTimeout(Duration.ofSeconds(5))
                .listener(calls)
                .circuitBreaker(CircuitBreakerOptions.builder().failureThreshold(1).build())
                .build();
        for (int i = 0; i < 3; i++) {
            client.evaluateAsync(REQUEST).toCompletableFuture().cancel(true);
        }
        calls.await(3);
        pdp.respond(json(200, "{\"decision\": true}"));

        assertTrue(client.evaluate(REQUEST).getDecision(), "cancelled calls say nothing about the PDP");
    }

    @Test
    void runsWhatFollowsABodyTimeoutOffTheDelayThread() {
        pdp.respond((request, exchange) -> {
            // Headers at once, the body never: only the client's own deadline ends the exchange
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write("{\"decision\"".getBytes(StandardCharsets.UTF_8));
                body.flush();
                sleep(Duration.ofSeconds(2));
            }
        });
        AuthZenClient client = AuthZenClient.builder()
                .pdpUrl(pdp.url())
                .requestTimeout(Duration.ofMillis(300))
                .build();

        String thread = threadThatSeesTheFailure(client.evaluateAsync(REQUEST));

        assertFalse(thread.contains("CompletableFutureDelayScheduler"), thread);
    }

    @Test
    void runsWhatFollowsACredentialTimeoutOffTheDelayThread() {
        AuthZenClient client = AuthZenClient.builder()
                .pdpUrl(pdp.url())
                .credentials(CredentialProvider.async(CompletableFuture::new))
                .requestTimeout(Duration.ofMillis(100))
                .build();

        String thread = threadThatSeesTheFailure(client.evaluateAsync(REQUEST));

        assertFalse(thread.contains("CompletableFutureDelayScheduler"), thread);
    }

    private static String threadThatSeesTheFailure(CompletionStage<?> stage) {
        CompletableFuture<String> thread = new CompletableFuture<>();
        stage.whenComplete((response, error) -> thread.complete(Thread.currentThread().getName()));
        return thread.orTimeout(5, TimeUnit.SECONDS).join();
    }

    private void assertNothingSentAfterTheCancel() {
        sleep(AFTER_RETRY);
        assertEquals(1, pdp.requests().size(), "the retry after the cancel must not be sent");
        CallCompleted<?, ?> call = calls.awaitOne();
        assertEquals(Outcome.CANCELLED, call.getOutcome());
    }

    private static Duration elapsedSince(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not met within 5 s");
            sleep(Duration.ofMillis(10));
        }
    }

    /** Records the completed calls. */
    private static final class Calls implements PepListener {

        private final List<CallCompleted<?, ?>> completed = new CopyOnWriteArrayList<>();

        @Override
        public void onCallCompleted(CallCompleted<?, ?> event) {
            completed.add(event);
        }

        CallCompleted<?, ?> awaitOne() {
            awaitCondition(() -> !completed.isEmpty());
            assertEquals(1, completed.size());
            return completed.get(0);
        }

        void await(int count) {
            awaitCondition(() -> completed.size() >= count);
        }
    }
}
