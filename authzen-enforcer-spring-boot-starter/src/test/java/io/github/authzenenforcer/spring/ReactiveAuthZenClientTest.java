package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.client.PaginationOptions;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.ActionSearchRequest;
import io.github.authzenenforcer.core.model.EvaluationItem;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.ResourceSearchRequest;
import io.github.authzenenforcer.core.model.SearchResponse;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.model.SubjectSearchRequest;
import io.github.authzenenforcer.spring.reactive.ReactiveAuthZenClient;

import org.junit.jupiter.api.Test;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reactive client: what it calls on the client, when, and what it signals. Its traces are
 * tested in {@link ReactiveTracingTest}.
 */
class ReactiveAuthZenClientTest {

    private static final Subject BOB = Subject.of("user", "bob");
    private static final Resource HANDBOOK = Resource.of("document", "handbook");
    private static final EvaluationRequest READ = EvaluationRequest.of(BOB, Action.of("read"), HANDBOOK);

    private final StubClient stub = new StubClient();
    private final ReactiveAuthZenClient reactive = new ReactiveAuthZenClient(stub.client());

    @Test
    void callsThePdpOnlyWhenSubscribed() {
        stub.answer("evaluateAsync", CompletableFuture.completedFuture(EvaluationResponse.of(true)));

        Mono<EvaluationResponse> decision = reactive.evaluate(READ);

        assertEquals(List.of(), stub.calls());
        assertTrue(decision.block().getDecision());
        assertEquals(List.of("evaluateAsync " + READ), stub.calls());
    }

    @Test
    void callsThePdpOncePerSubscription() {
        stub.answer("evaluateAsync", CompletableFuture.completedFuture(EvaluationResponse.of(false)));
        Mono<EvaluationResponse> decision = reactive.evaluate(READ);

        decision.block();
        decision.block();

        assertEquals(2, stub.calls().size());
    }

    @Test
    void cancellingTheSubscriptionCancelsTheCall() throws InterruptedException {
        CompletableFuture<EvaluationResponse> call = new CompletableFuture<>();
        stub.answer("evaluateAsync", call);

        Disposable subscription = reactive.evaluate(READ).subscribe();
        // The call starts on another thread
        awaitCalls(1);
        subscription.dispose();

        assertTrue(call.isCancelled());
    }

    @Test
    void startsTheCallOffTheSubscribingThread() {
        stub.answer("evaluateAsync", CompletableFuture.completedFuture(EvaluationResponse.of(true)));

        reactive.evaluate(READ).block();

        assertTrue(stub.threads().get(0).startsWith("boundedElastic"), stub.threads().toString());
    }

    @Test
    void signalsTheFailureOfTheCall() {
        stub.answer("evaluateAsync", CompletableFuture.failedFuture(new PdpCommunicationException("unreachable")));

        Mono<EvaluationResponse> decision = reactive.evaluate(READ);

        assertEquals("unreachable", assertThrows(PdpCommunicationException.class, decision::block).getMessage());
    }

    @Test
    void asksForSeveralDecisions() {
        EvaluationsRequest request = EvaluationsRequest.builder()
                .subject(BOB)
                .resource(HANDBOOK)
                .evaluation(EvaluationItem.builder().action(Action.of("read")).build())
                .build();
        EvaluationsResponse response = EvaluationsResponse.of(List.of(EvaluationResponse.of(true)), 1);
        stub.answer("evaluationsAsync", CompletableFuture.completedFuture(response));

        assertSame(response, reactive.evaluations(request).block());
        assertEquals(List.of("evaluationsAsync " + request), stub.calls());
    }

    @Test
    void searchesOnePage() {
        SubjectSearchRequest subjects = SubjectSearchRequest.of("user", Action.of("read"), HANDBOOK);
        ResourceSearchRequest resources = ResourceSearchRequest.of(BOB, Action.of("read"), "document");
        ActionSearchRequest actions = ActionSearchRequest.of(BOB, HANDBOOK);
        stub.answer("searchSubjectsAsync", CompletableFuture.completedFuture(
                SearchResponse.<Subject>builder().result(BOB).build()));
        stub.answer("searchResourcesAsync", CompletableFuture.completedFuture(
                SearchResponse.<Resource>builder().result(HANDBOOK).build()));
        stub.answer("searchActionsAsync", CompletableFuture.completedFuture(
                SearchResponse.<Action>builder().result(Action.of("read")).build()));

        assertEquals(List.of(BOB), reactive.searchSubjects(subjects).block().getResults());
        assertEquals(List.of(HANDBOOK), reactive.searchResources(resources).block().getResults());
        assertEquals(List.of(Action.of("read")), reactive.searchActions(actions).block().getResults());
    }

    @Test
    void searchesAllPages() {
        SubjectSearchRequest subjects = SubjectSearchRequest.of("user", Action.of("read"), HANDBOOK);
        ResourceSearchRequest resources = ResourceSearchRequest.of(BOB, Action.of("read"), "document");
        ActionSearchRequest actions = ActionSearchRequest.of(BOB, HANDBOOK);
        PaginationOptions options = PaginationOptions.builder().maxPages(2).build();
        stub.answer("searchAllSubjectsAsync", publisher(BOB, Subject.of("user", "carol")));
        stub.answer("searchAllResourcesAsync", publisher(HANDBOOK));
        stub.answer("searchAllActionsAsync", publisher(Action.of("read"), Action.of("update")));

        assertEquals(List.of(BOB, Subject.of("user", "carol")), reactive.searchAllSubjects(subjects).collectList().block());
        assertEquals(List.of(HANDBOOK), reactive.searchAllResources(resources, options).collectList().block());
        assertEquals(List.of(Action.of("read"), Action.of("update")),
                reactive.searchAllActions(actions).collectList().block());
        assertEquals(List.of("searchAllSubjectsAsync " + subjects, "searchAllResourcesAsync " + resources + " " + options,
                "searchAllActionsAsync " + actions), stub.calls());
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> new ReactiveAuthZenClient(null));
        assertThrows(NullPointerException.class, () -> reactive.evaluate(null));
        assertThrows(NullPointerException.class, () -> reactive.evaluations(null));
        assertThrows(NullPointerException.class, () -> reactive.searchAllSubjects(
                SubjectSearchRequest.of("user", Action.of("read"), HANDBOOK), null));
    }

    @Test
    void returnsTheClient() {
        assertSame(stub.client(), reactive.client());
    }

    private void awaitCalls(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (stub.calls().size() < count) {
            assertTrue(System.nanoTime() < deadline, "Timed out");
            Thread.sleep(10);
        }
    }

    @SafeVarargs
    private static <T> Flow.Publisher<T> publisher(T... items) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private int next;
            private boolean done;

            @Override
            public void request(long n) {
                for (long i = 0; i < n && next < items.length; i++) {
                    subscriber.onNext(items[next++]);
                }
                if (next == items.length && !done) {
                    done = true;
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                done = true;
            }
        });
    }

    /** A client that answers each method with what the test gave it, and records the calls. */
    private static final class StubClient {

        private final Map<String, Object> answers = new HashMap<>();
        private final List<String> calls = new ArrayList<>();
        private final List<String> threads = new ArrayList<>();
        private final AuthZenClient client = (AuthZenClient) Proxy.newProxyInstance(
                AuthZenClient.class.getClassLoader(), new Class<?>[] {AuthZenClient.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.getName().equals("equals") ? proxy == args[0]
                                : method.getName().equals("hashCode") ? System.identityHashCode(proxy) : "stub";
                    }
                    synchronized (this) {
                        threads.add(Thread.currentThread().getName());
                        calls.add(method.getName() + " " + String.join(" ",
                                Arrays.stream(args).map(String::valueOf).toList()));
                        return answers.get(method.getName());
                    }
                });

        AuthZenClient client() {
            return client;
        }

        synchronized void answer(String method, Object answer) {
            answers.put(method, answer);
        }

        synchronized List<String> calls() {
            return List.copyOf(calls);
        }

        synchronized List<String> threads() {
            return List.copyOf(threads);
        }
    }
}
