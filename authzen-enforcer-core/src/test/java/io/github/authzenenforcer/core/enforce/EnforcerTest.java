package io.github.authzenenforcer.core.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpAuthenticationException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.enforce.EnforcementResult.Outcome;
import io.github.authzenenforcer.core.event.EnforcementCompleted;
import io.github.authzenenforcer.core.internal.client.TestLoggerFinder;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.spi.PepListener;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class EnforcerTest {

    private static final Map<String, Object> ALICE = Map.of("sub", "alice-id");
    private static final RequestDescription READ = RequestDescription.builder()
            .method("GET").routePattern("/documents/{id}").pathVariable("id", "42").claims(ALICE).build();
    private static final EvaluationRequest READ_EVALUATION = EvaluationRequest.of(
            Subject.of("user", "alice-id"), Action.of("read"), Resource.of("documents", "42"));

    /** A client whose evaluations complete when the test says so. */
    private static final class StubClient {

        private final BlockingQueue<EvaluationRequest> requests = new LinkedBlockingQueue<>();
        private final AtomicReference<CompletableFuture<EvaluationResponse>> next =
                new AtomicReference<>(new CompletableFuture<>());

        AuthZenClient client() {
            return (AuthZenClient) Proxy.newProxyInstance(AuthZenClient.class.getClassLoader(),
                    new Class<?>[] {AuthZenClient.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "evaluateAsync":
                                requests.add((EvaluationRequest) args[0]);
                                return next.get();
                            case "toString":
                                return "StubClient";
                            default:
                                throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        StubClient answer(boolean decision) {
            next.get().complete(EvaluationResponse.of(decision));
            return this;
        }

        StubClient fail(Throwable failure) {
            next.get().completeExceptionally(failure);
            return this;
        }

        CompletableFuture<EvaluationResponse> call() {
            return next.get();
        }
    }

    /** Collects enforcement events. */
    private static final class Events implements PepListener {

        private final BlockingQueue<EnforcementCompleted> events = new LinkedBlockingQueue<>();

        @Override
        public void onEnforcementCompleted(EnforcementCompleted event) {
            events.add(event);
        }

        EnforcementCompleted next() throws InterruptedException {
            EnforcementCompleted event = events.poll(5, TimeUnit.SECONDS);
            assertTrue(event != null, "no event arrived");
            return event;
        }
    }

    @Test
    void letsAPermittedRequestThrough() {
        StubClient pdp = new StubClient().answer(true);

        EnforcementResult result = Enforcer.builder(pdp.client()).build().enforce(READ, PermissionSpec.none());

        assertEquals(Outcome.PERMITTED, result.getOutcome());
        assertTrue(result.isAllowed());
        assertEquals(OptionalInt.empty(), result.getStatus());
        assertEquals(READ_EVALUATION, result.getRequest().orElseThrow());
        assertEquals(READ_EVALUATION, pdp.requests.poll());
    }

    @Test
    void deniesWithForbiddenAndKeepsTheResponseForHooks() {
        StubClient pdp = new StubClient();
        EvaluationResponse deny = EvaluationResponse.of(false, Context.of(Map.of("reason", "not the owner")));
        pdp.call().complete(deny);

        EnforcementResult result = Enforcer.builder(pdp.client()).build().enforce(READ, PermissionSpec.none());

        assertEquals(Outcome.DENIED, result.getOutcome());
        assertFalse(result.isAllowed());
        assertEquals(OptionalInt.of(403), result.getStatus());
        assertSame(deny, result.getResponse().orElseThrow());
    }

    @Test
    void doesNotAskThePdpAboutAPreflight() {
        StubClient pdp = new StubClient();
        RequestDescription preflight = RequestDescription.builder().method("OPTIONS").routePattern("/documents/{id}")
                .pathVariable("id", "42").build();

        EnforcementResult result = Enforcer.builder(pdp.client()).build().enforce(preflight, PermissionSpec.none());

        assertEquals(Outcome.NOT_ENFORCED, result.getOutcome());
        assertTrue(result.isAllowed());
        assertTrue(pdp.requests.isEmpty());
    }

    @Test
    void answersAnAnonymousCallerWithUnauthorized() {
        StubClient pdp = new StubClient();
        RequestDescription anonymous = RequestDescription.builder().method("GET").routePattern("/documents/{id}")
                .pathVariable("id", "42").build();

        EnforcementResult result = Enforcer.builder(pdp.client()).build().enforce(anonymous, PermissionSpec.none());

        assertEquals(Outcome.UNAUTHENTICATED, result.getOutcome());
        assertEquals(OptionalInt.of(401), result.getStatus());
        assertTrue(pdp.requests.isEmpty());
    }

    @Test
    void refusesWhatCannotBeInferredWithoutAskingThePdp() {
        StubClient pdp = new StubClient();
        RequestDescription unsafe = RequestDescription.builder().method("GET").routePattern("/{tenant}/documents")
                .pathVariable("tenant", "acme").claims(ALICE).build();

        EnforcementResult result = Enforcer.builder(pdp.client()).build().enforce(unsafe, PermissionSpec.none());

        assertEquals(Outcome.REFUSED, result.getOutcome());
        assertEquals(OptionalInt.of(403), result.getStatus());
        assertTrue(result.getReason().orElseThrow().contains("/{tenant}/documents"));
        assertFalse(result.getReason().orElseThrow().contains("acme"));
        assertTrue(pdp.requests.isEmpty());
    }

    @Test
    void deniesWhenThePdpFails() {
        PdpCommunicationException failure = new PdpCommunicationException("PDP unreachable");
        StubClient pdp = new StubClient().fail(failure);

        EnforcementResult result = Enforcer.builder(pdp.client()).build().enforce(READ, PermissionSpec.none());

        assertEquals(Outcome.FAILED, result.getOutcome());
        assertFalse(result.isAllowed());
        assertEquals(OptionalInt.of(403), result.getStatus());
        assertSame(failure, result.getFailure().orElseThrow());
        assertEquals(READ_EVALUATION, result.getRequest().orElseThrow());
    }

    @Test
    void answersAFailureWithTheStatusOfThePolicy() {
        StubClient pdp = new StubClient().fail(new PdpCommunicationException("PDP unreachable"));
        Enforcer enforcer = Enforcer.builder(pdp.client())
                .policy(EnforcementPolicy.builder().errorStatus(503).build())
                .build();

        EnforcementResult result = enforcer.enforce(READ, PermissionSpec.none());

        assertEquals(Outcome.FAILED, result.getOutcome());
        assertEquals(OptionalInt.of(503), result.getStatus());
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 401, 404, 500, 502})
    void acceptsOnlyForbiddenAndUnavailableAsErrorStatus(int status) {
        EnforcementPolicy.Builder policy = EnforcementPolicy.builder();

        assertThrows(IllegalArgumentException.class, () -> policy.errorStatus(status));
    }

    @Test
    void wrapsAFailureThatIsNoAuthZenException() {
        IllegalStateException bug = new IllegalStateException("bug in a custom client");
        StubClient pdp = new StubClient().fail(bug);

        EnforcementResult result = Enforcer.builder(pdp.client()).build().enforce(READ, PermissionSpec.none());

        assertEquals(Outcome.FAILED, result.getOutcome());
        assertSame(bug, result.getFailure().orElseThrow().getCause());
    }

    @Test
    void deniesWhenTheClientThrowsInsteadOfFailingTheStage() {
        AuthZenClient throwing = (AuthZenClient) Proxy.newProxyInstance(AuthZenClient.class.getClassLoader(),
                new Class<?>[] {AuthZenClient.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("broken");
                });
        Enforcer enforcer = Enforcer.builder(throwing).build();

        assertEquals(Outcome.FAILED, enforcer.enforce(READ, PermissionSpec.none()).getOutcome());
        assertEquals(Outcome.FAILED,
                enforcer.enforceAsync(READ, PermissionSpec.none()).toCompletableFuture().join().getOutcome());
    }

    @Test
    void deniesWhenAnInferenceComponentThrowsEvenIfFailOpen() {
        StubClient pdp = new StubClient().answer(true);
        RequestInferrer inferrer = RequestInferrer.builder()
                .subjectResolver(claims -> {
                    throw new IllegalStateException("broken resolver");
                })
                .build();
        Enforcer enforcer = Enforcer.builder(pdp.client())
                .inferrer(inferrer)
                .policy(EnforcementPolicy.builder().failOpen(true).build())
                .build();

        EnforcementResult result = enforcer.enforce(READ, PermissionSpec.none());

        assertEquals(Outcome.FAILED, result.getOutcome());
        assertFalse(result.isAllowed());
        assertTrue(pdp.requests.isEmpty());
    }

    @Test
    void failsOpenOnlyWhenThePdpGivesNoDecision() {
        EnforcementPolicy failOpen = EnforcementPolicy.builder().failOpen(true).build();
        PdpCommunicationException failure = new PdpCommunicationException("PDP unreachable");

        EnforcementResult failed = Enforcer.builder(new StubClient().fail(failure).client()).policy(failOpen).build()
                .enforce(READ, PermissionSpec.none());
        EnforcementResult denied = Enforcer.builder(new StubClient().answer(false).client()).policy(failOpen).build()
                .enforce(READ, PermissionSpec.none());
        EnforcementResult refused = Enforcer.builder(new StubClient().answer(true).client()).policy(failOpen).build()
                .enforce(RequestDescription.builder().method("GET").routePattern("/{tenant}/documents")
                        .pathVariable("tenant", "acme").claims(ALICE).build(), PermissionSpec.none());
        EnforcementResult anonymous = Enforcer.builder(new StubClient().answer(true).client()).policy(failOpen).build()
                .enforce(RequestDescription.builder().method("GET").routePattern("/documents").build(),
                        PermissionSpec.none());

        assertEquals(Outcome.FAILED_OPEN, failed.getOutcome());
        assertTrue(failed.isAllowed());
        assertEquals(OptionalInt.empty(), failed.getStatus());
        assertSame(failure, failed.getFailure().orElseThrow());
        assertEquals(Outcome.DENIED, denied.getOutcome());
        assertEquals(Outcome.REFUSED, refused.getOutcome());
        assertEquals(Outcome.UNAUTHENTICATED, anonymous.getOutcome());
    }

    static Stream<Throwable> failuresThatAreNotAnUnavailablePdp() {
        return Stream.of(
                new PdpAuthenticationException("PDP rejected the PEP's credential", 401),
                new PdpProtocolException("PDP response is not valid JSON"),
                new AuthZenException("Credential provider failed to supply a credential"),
                new IllegalStateException("bug in a custom client"),
                new StackOverflowError(),
                new CancellationException("cancelled by someone else"));
    }

    @ParameterizedTest
    @MethodSource("failuresThatAreNotAnUnavailablePdp")
    void failsOpenOnlyWhileThePdpIsUnavailable(Throwable failure) {
        StubClient pdp = new StubClient().fail(failure);
        Enforcer enforcer = Enforcer.builder(pdp.client())
                .policy(EnforcementPolicy.builder().failOpen(true).build())
                .build();

        EnforcementResult result = enforcer.enforce(READ, PermissionSpec.none());

        assertEquals(Outcome.FAILED, result.getOutcome());
        assertFalse(result.isAllowed());
        assertEquals(OptionalInt.of(403), result.getStatus());
    }

    @Test
    void warnsAboutFailOpenAtStartupAndWhenItLetsARequestThrough() {
        TestLoggerFinder.clearRecords();
        Enforcer enforcer = Enforcer.builder(new StubClient().fail(new PdpCommunicationException("down")).client())
                .policy(EnforcementPolicy.builder().failOpen(true).build())
                .build();

        enforcer.enforce(READ, PermissionSpec.none());
        enforcer.enforce(READ, PermissionSpec.none());

        List<String> warnings = TestLoggerFinder.records().stream()
                .filter(record -> record.startsWith("WARNING " + Enforcer.class.getName()))
                .toList();
        assertEquals(2, warnings.size(), warnings::toString);
        assertTrue(warnings.get(0).contains("Fail-open is enabled"));
        assertTrue(warnings.get(1).contains("PdpCommunicationException"));
        assertFalse(warnings.get(1).contains("down"));
    }

    @Test
    void doesNotWarnWithoutFailOpen() {
        TestLoggerFinder.clearRecords();

        Enforcer.builder(new StubClient().fail(new PdpCommunicationException("down")).client()).build()
                .enforce(READ, PermissionSpec.none());

        assertTrue(TestLoggerFinder.records().stream()
                .noneMatch(record -> record.startsWith("WARNING " + Enforcer.class.getName())));
    }

    @Test
    void decidesAsynchronously() {
        StubClient pdp = new StubClient();
        CompletionStage<EnforcementResult> stage = Enforcer.builder(pdp.client()).build()
                .enforceAsync(READ, PermissionSpec.none());

        assertFalse(stage.toCompletableFuture().isDone());
        pdp.answer(true);

        assertEquals(Outcome.PERMITTED, stage.toCompletableFuture().join().getOutcome());
    }

    @Test
    void completesTheAsynchronousStageWithAFailureAsAResult() {
        StubClient pdp = new StubClient().fail(new PdpCommunicationException("down"));

        EnforcementResult result = Enforcer.builder(pdp.client()).build()
                .enforceAsync(READ, PermissionSpec.none()).toCompletableFuture().join();

        assertEquals(Outcome.FAILED, result.getOutcome());
    }

    @Test
    void refusesToLetTheHolderCompleteTheStage() {
        CompletionStage<EnforcementResult> stage = Enforcer.builder(new StubClient().client()).build()
                .enforceAsync(READ, PermissionSpec.none());

        assertThrows(UnsupportedOperationException.class, () -> ((CompletableFuture<EnforcementResult>) stage)
                .complete(null));
    }

    @Test
    void stopsThePdpCallWhenTheStageIsCancelled() throws InterruptedException {
        StubClient pdp = new StubClient();
        Events events = new Events();
        CompletionStage<EnforcementResult> stage = Enforcer.builder(pdp.client()).listener(events).build()
                .enforceAsync(READ, PermissionSpec.none());

        assertTrue(stage.toCompletableFuture().cancel(true));

        assertTrue(pdp.call().isCancelled());
        assertTrue(((CompletableFuture<EnforcementResult>) stage).isCancelled());
        EnforcementCompleted event = events.next();
        assertTrue(event.isCancelled());
        assertTrue(event.getOutcome().isEmpty());
    }

    @Test
    void stopsThePdpCallWhenTheWaitingThreadIsInterrupted() throws InterruptedException {
        StubClient pdp = new StubClient();
        Enforcer enforcer = Enforcer.builder(pdp.client()).build();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            try {
                enforcer.enforce(READ, PermissionSpec.none());
            } catch (RuntimeException e) {
                thrown.set(e);
            }
            interrupted.set(Thread.currentThread().isInterrupted());
            done.countDown();
        });
        caller.start();
        assertEquals(READ_EVALUATION, pdp.requests.poll(5, TimeUnit.SECONDS));

        caller.interrupt();

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertInstanceOf(CancellationException.class, thrown.get());
        assertTrue(interrupted.get());
        assertTrue(pdp.call().isCancelled());
    }

    @Test
    void reportsEveryOutcomeToItsListeners() throws InterruptedException {
        Events events = new Events();
        Enforcer enforcer = Enforcer.builder(new StubClient().fail(new PdpCommunicationException("down")).client())
                .listener(events)
                .build();

        enforcer.enforce(READ, PermissionSpec.none());
        enforcer.enforce(RequestDescription.builder().method("GET").routePattern("/{tenant}/documents")
                .pathVariable("tenant", "acme").claims(ALICE).build(), PermissionSpec.none());

        EnforcementCompleted failed = events.next();
        assertEquals(Outcome.FAILED, failed.getOutcome().orElseThrow());
        assertEquals("GET", failed.getMethod());
        assertEquals("/documents/{id}", failed.getRoutePattern());
        assertInstanceOf(PdpCommunicationException.class, failed.getFailure().orElseThrow());
        assertFalse(failed.getDuration().isNegative());
        EnforcementCompleted refused = events.next();
        assertEquals(Outcome.REFUSED, refused.getOutcome().orElseThrow());
        assertTrue(refused.getReason().isPresent());
        assertFalse(refused.toString().contains("acme"));
    }

    @Test
    void keepsDecidingWhenAListenerFails() {
        PepListener failing = new PepListener() {
            @Override
            public void onEnforcementCompleted(EnforcementCompleted event) {
                throw new IllegalStateException("listener bug");
            }
        };
        Enforcer enforcer = Enforcer.builder(new StubClient().answer(true).client()).listener(failing).build();

        assertEquals(Outcome.PERMITTED, enforcer.enforce(READ, PermissionSpec.none()).getOutcome());
        assertEquals(Outcome.PERMITTED, enforcer.enforce(READ, PermissionSpec.none()).getOutcome());
    }

    @Test
    void checksRoutesWithItsInferrer() {
        Enforcer enforcer = Enforcer.builder(new StubClient().client()).build();

        assertTrue(enforcer.check("GET", "/{tenant}/documents", PermissionSpec.none()).isPresent());
        assertTrue(enforcer.check("GET", "/documents/{id}", PermissionSpec.none()).isEmpty());
    }

    @Test
    void deniesWhenTheRealClientCannotReachThePdp() {
        AuthZenClient client = AuthZenClient.builder()
                .pdpUrl(URI.create("http://127.0.0.1:1"))
                .evaluationEndpoint(URI.create("http://127.0.0.1:1/access/v1/evaluation"))
                .connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(2))
                .build();

        EnforcementResult result = Enforcer.builder(client).build().enforce(READ, PermissionSpec.none());

        assertEquals(Outcome.FAILED, result.getOutcome());
        assertInstanceOf(AuthZenException.class, result.getFailure().orElseThrow());
        assertFalse(result.isAllowed());
    }

    @Test
    void masksTheRequestInItsString() {
        EnforcementResult result = Enforcer.builder(new StubClient().answer(false).client()).build()
                .enforce(READ, PermissionSpec.none());

        assertFalse(result.toString().contains("alice-id"));
        assertEquals("EnforcementResult{DENIED, status=403}", result.toString());
    }
}
