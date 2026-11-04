package io.github.authzenenforcer.core.client;

import static io.github.authzenenforcer.core.client.FakePdp.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.FakePdp.RecordedRequest;
import io.github.authzenenforcer.core.client.FakePdp.Responder;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.ActionSearchRequest;
import io.github.authzenenforcer.core.model.PageRequest;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.ResourceSearchRequest;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.model.SubjectSearchRequest;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthZenClientPaginationTest {

    private static final Subject ALICE = Subject.of("user", "alice");
    private static final ResourceSearchRequest ACCOUNTS = ResourceSearchRequest.builder()
            .subject(ALICE).action(Action.of("can_read")).resourceType("account")
            .page(PageRequest.ofLimit(2))
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
                .credentials(CredentialProvider.staticBearer("token-1"))
                .requestTimeout(Duration.ofSeconds(2))
                .maxRetries(0);
    }

    /** Answers by the request's page token: "" for the first page, then the tokens handed out. */
    private static Responder pages(Map<String, String> bodies) {
        return (request, exchange) -> {
            String body = bodies.get(token(request));
            if (body == null) {
                FakePdp.send(exchange, 400, new byte[0], request.header("X-Request-ID"));
            } else {
                json(200, body).respond(request, exchange);
            }
        };
    }

    private static String token(RecordedRequest request) {
        Map<?, ?> json = (Map<?, ?>) JsonCodec.defaultCodec().read(request.body());
        Map<?, ?> page = (Map<?, ?>) json.get("page");
        return page != null && page.get("token") != null ? (String) page.get("token") : "";
    }

    private static String page(String nextToken, String... ids) {
        String results = Stream.of(ids).map(id -> "{\"type\": \"account\", \"id\": \"" + id + "\"}")
                .collect(Collectors.joining(", "));
        return "{\"page\": {\"next_token\": \"" + nextToken + "\"}, \"results\": [" + results + "]}";
    }

    private static List<Resource> accounts(String... ids) {
        return Stream.of(ids).map(id -> Resource.of("account", id)).collect(Collectors.toList());
    }

    private void threePages() {
        pdp.respond(pages(Map.of(
                "", page("t2", "1", "2"),
                "t2", page("t3", "3", "4"),
                "t3", page("", "5"))));
    }

    @Test
    void walksAllPagesWithTheSameRequestAndTheNextToken() {
        threePages();

        List<Resource> all = builder().build().searchAllResources(ACCOUNTS).collect(Collectors.toList());

        assertEquals(accounts("1", "2", "3", "4", "5"), all);
        List<RecordedRequest> requests = pdp.requests();
        assertEquals(3, requests.size());
        Map<?, ?> first = (Map<?, ?>) JsonCodec.defaultCodec().read(requests.get(0).body());
        for (int i = 1; i < 3; i++) {
            Map<?, ?> next = (Map<?, ?>) JsonCodec.defaultCodec().read(requests.get(i).body());
            assertEquals(Map.of("token", "t" + (i + 1), "limit", 2L), next.get("page"));
            assertEquals(first.get("subject"), next.get("subject"));
            assertEquals(first.get("action"), next.get("action"));
            assertEquals(first.get("resource"), next.get("resource"));
        }
    }

    @Test
    void fetchesAPageOnlyWhenItsResultsAreNeeded() {
        threePages();
        AuthZenClient client = builder().build();

        Stream<Resource> stream = client.searchAllResources(ACCOUNTS);
        assertTrue(pdp.requests().isEmpty());

        assertEquals(accounts("1", "2", "3"), stream.limit(3).collect(Collectors.toList()));
        assertEquals(2, pdp.requests().size());
    }

    @Test
    void doesNotSplitAheadOfTheConsumer() {
        threePages();
        AuthZenClient client = builder().build();

        assertNull(client.searchAllResources(ACCOUNTS).spliterator().trySplit());
        assertTrue(pdp.requests().isEmpty());
        assertEquals(accounts("1", "2", "3", "4", "5"),
                client.searchAllResources(ACCOUNTS).parallel().collect(Collectors.toList()));
        assertEquals(3, pdp.requests().size());
    }

    @Test
    void startsAtThePageOfATokenInTheFirstRequest() {
        threePages();

        List<Resource> rest = builder().build().searchAllResources(ACCOUNTS.withPageToken("t3"))
                .collect(Collectors.toList());

        assertEquals(accounts("5"), rest);
        assertEquals(1, pdp.requests().size());
    }

    @Test
    void passesDuplicatesOnAsTheyCome() {
        pdp.respond(pages(Map.of("", page("t2", "1", "2"), "t2", page("", "2", "3"))));

        assertEquals(accounts("1", "2", "2", "3"),
                builder().build().searchAllResources(ACCOUNTS).collect(Collectors.toList()));
    }

    @Test
    void endsWithoutAPageObject() {
        pdp.respond(json(200, "{\"results\": [{\"name\": \"can_read\"}, {\"name\": \"can_write\"}]}"));

        assertEquals(List.of(Action.of("can_read"), Action.of("can_write")),
                builder().build().searchAllActions(ActionSearchRequest.of(ALICE, Resource.of("account", "1")))
                        .collect(Collectors.toList()));
        assertEquals(1, pdp.requests().size());
    }

    @Test
    void rejectsTheWholeResponseThatRepeatsAPageToken() {
        pdp.respond(pages(Map.of("", page("t2", "1"), "t2", page("t2", "2"))));
        Iterator<Resource> results = builder().build().searchAllResources(ACCOUNTS).iterator();

        assertEquals(Resource.of("account", "1"), results.next());
        PdpProtocolException e = assertThrows(PdpProtocolException.class, results::hasNext);
        assertTrue(e.getMessage().contains("repeats an earlier page token"));
        assertEquals(2, pdp.requests().size());
    }

    @Test
    void rejectsAFirstTokenThatComesBack() {
        pdp.respond(pages(Map.of("t2", page("t2", "1"))));
        Stream<Resource> stream = builder().build().searchAllResources(ACCOUNTS.withPageToken("t2"));

        assertThrows(PdpProtocolException.class, () -> stream.collect(Collectors.toList()));
    }

    @Test
    void stopsBeforeAPageBeyondMaxPages() {
        threePages();
        AuthZenClient client = builder().pagination(PaginationOptions.builder().maxPages(2).build()).build();
        Stream<Resource> stream = client.searchAllResources(ACCOUNTS);

        AuthZenException e = assertThrows(AuthZenException.class, () -> stream.collect(Collectors.toList()));
        assertEquals("Resource search takes more than 2 pages (PaginationOptions.maxPages)", e.getMessage());
        assertEquals(2, pdp.requests().size());
    }

    @Test
    void stopsBeforeAResultBeyondMaxResults() {
        threePages();
        AuthZenClient client = builder().pagination(PaginationOptions.builder().maxResults(3).build()).build();
        Iterator<Resource> results = client.searchAllResources(ACCOUNTS).iterator();

        assertEquals(accounts("1", "2", "3"), List.of(results.next(), results.next(), results.next()));
        AuthZenException e = assertThrows(AuthZenException.class, results::next);
        assertEquals("Resource search returns more than 3 results (PaginationOptions.maxResults)", e.getMessage());
    }

    @Test
    void reachesExactlyTheLimitsWithoutFailing() {
        threePages();
        AuthZenClient client = builder()
                .pagination(PaginationOptions.builder().maxPages(3).maxResults(5).build()).build();

        assertEquals(5, client.searchAllResources(ACCOUNTS).count());
    }

    @Test
    void usesTheLimitsOfASingleSearchInsteadOfTheClients() {
        threePages();
        AuthZenClient client = builder().pagination(PaginationOptions.builder().maxPages(1).build()).build();

        assertEquals(5, client.searchAllResources(ACCOUNTS, PaginationOptions.defaults()).count());
        Stream<Resource> limited = client.searchAllResources(ACCOUNTS);
        assertThrows(AuthZenException.class, limited::count);
    }

    @Test
    void throwsAFailureOnALaterPageAfterTheEarlierResults() {
        pdp.respond(FakePdp.sequence(json(200, page("t2", "1")), json(500, "")));
        Iterator<Resource> results = builder().build().searchAllResources(ACCOUNTS).iterator();

        assertEquals(Resource.of("account", "1"), results.next());
        assertThrows(PdpCommunicationException.class, results::hasNext);
    }

    @Test
    void keepsFailingAfterACaughtFailure() {
        pdp.respond(FakePdp.sequence(json(200, page("t2", "1")), json(500, ""), json(200, page("", "2"))));
        Iterator<Resource> results = builder().build().searchAllResources(ACCOUNTS).iterator();
        results.next();

        PdpCommunicationException first = assertThrows(PdpCommunicationException.class, results::hasNext);
        PdpCommunicationException again = assertThrows(PdpCommunicationException.class, results::hasNext);

        assertSame(first, again);
        assertEquals(2, pdp.requests().size());
    }

    @Test
    void keepsFailingAfterARepeatedToken() {
        pdp.respond(pages(Map.of("", page("t2", "1"), "t2", page("t2", "2"))));
        Iterator<Resource> results = builder().build().searchAllResources(ACCOUNTS).iterator();
        results.next();

        assertThrows(PdpProtocolException.class, results::hasNext);
        assertThrows(PdpProtocolException.class, results::hasNext);
    }

    @Test
    void stopsWaitingForAPageWhenInterrupted() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        pdp.respond((request, exchange) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            json(200, page("", "1")).respond(request, exchange);
        });
        Stream<Resource> stream = builder().build().searchAllResources(ACCOUNTS);
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
        Thread consumer = new Thread(() -> {
            try {
                stream.count();
                failure.complete(null);
            } catch (RuntimeException e) {
                failure.complete(e);
            }
            interrupted.complete(Thread.currentThread().isInterrupted());
        });
        try {
            consumer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pdp.requests().isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            consumer.interrupt();

            Throwable error = failure.get(5, TimeUnit.SECONDS);
            assertInstanceOf(PdpCommunicationException.class, error);
            assertEquals("Interrupted while waiting for the PDP", error.getMessage());
            assertTrue(interrupted.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            consumer.join(5000);
        }
    }

    @Test
    void rejectsNullArguments() {
        AuthZenClient client = builder().build();

        assertThrows(NullPointerException.class, () -> client.searchAllSubjects(null));
        assertThrows(NullPointerException.class, () -> client.searchAllResources(ACCOUNTS, null));
        assertThrows(NullPointerException.class, () -> client.searchAllActionsAsync(null));
        assertThrows(NullPointerException.class, () -> client.searchAllSubjectsAsync(
                SubjectSearchRequest.of("user", Action.of("can_read"), Resource.of("account", "1")), null));
        assertThrows(NullPointerException.class,
                () -> AuthZenClient.builder().pagination(null));
    }

    @Test
    void publishesAllPagesAsynchronously() throws Exception {
        threePages();
        Collector<Resource> collector = new Collector<>(Long.MAX_VALUE);

        builder().build().searchAllResourcesAsync(ACCOUNTS).subscribe(collector);

        collector.done.get(5, TimeUnit.SECONDS);
        assertEquals(accounts("1", "2", "3", "4", "5"), collector.results());
        assertEquals(3, pdp.requests().size());
    }

    @Test
    void fetchesAsynchronouslyOnlyWhatTheSubscriberAsksFor() throws Exception {
        threePages();
        Collector<Resource> collector = new Collector<>(0);
        Flow.Publisher<Resource> publisher = builder().build().searchAllResourcesAsync(ACCOUNTS);

        publisher.subscribe(collector);
        assertTrue(pdp.requests().isEmpty());

        collector.request(3);
        collector.awaitResults(3);
        assertEquals(accounts("1", "2", "3"), collector.results());
        assertEquals(2, pdp.requests().size());

        collector.subscription.cancel();
        assertEquals(2, pdp.requests().size());
    }

    @Test
    void givesEachSubscriberItsOwnSearch() throws Exception {
        threePages();
        Flow.Publisher<Resource> publisher = builder().build().searchAllResourcesAsync(ACCOUNTS);
        Collector<Resource> first = new Collector<>(Long.MAX_VALUE);
        Collector<Resource> second = new Collector<>(Long.MAX_VALUE);

        publisher.subscribe(first);
        first.done.get(5, TimeUnit.SECONDS);
        publisher.subscribe(second);
        second.done.get(5, TimeUnit.SECONDS);

        assertEquals(first.results(), second.results());
        assertEquals(6, pdp.requests().size());
    }

    @Test
    void signalsAFailureAsAnAuthZenException() {
        pdp.respond(FakePdp.sequence(json(200, page("t2", "1")), json(500, "")));
        Collector<Resource> collector = new Collector<>(Long.MAX_VALUE);

        builder().build().searchAllResourcesAsync(ACCOUNTS).subscribe(collector);

        Throwable error = assertThrows(Exception.class, () -> collector.done.get(5, TimeUnit.SECONDS)).getCause();
        assertInstanceOf(PdpCommunicationException.class, error);
        assertEquals(accounts("1"), collector.results());
    }

    @Test
    void signalsALimitAsAnAuthZenException() {
        threePages();
        Collector<Resource> collector = new Collector<>(Long.MAX_VALUE);

        builder().build().searchAllResourcesAsync(ACCOUNTS, PaginationOptions.builder().maxPages(1).build())
                .subscribe(collector);

        Throwable error = assertThrows(Exception.class, () -> collector.done.get(5, TimeUnit.SECONDS)).getCause();
        assertEquals("Resource search takes more than 1 pages (PaginationOptions.maxPages)", error.getMessage());
        assertSame(AuthZenException.class, error.getClass());
        assertEquals(accounts("1", "2"), collector.results());
    }

    @Test
    void publishesSubjectsAndActionsToo() throws Exception {
        pdp.respond(json(200, "{\"results\": [{\"type\": \"user\", \"id\": \"alice\"}]}"));
        Collector<Subject> subjects = new Collector<>(Long.MAX_VALUE);

        builder().build().searchAllSubjectsAsync(
                SubjectSearchRequest.of("user", Action.of("can_read"), Resource.of("account", "1")))
                .subscribe(subjects);

        subjects.done.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(ALICE), subjects.results());

        pdp.respond(json(200, "{\"results\": [{\"name\": \"can_read\"}]}"));
        Collector<Action> actions = new Collector<>(Long.MAX_VALUE);
        builder().build().searchAllActionsAsync(ActionSearchRequest.of(ALICE, Resource.of("account", "1")))
                .subscribe(actions);
        actions.done.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(Action.of("can_read")), actions.results());
        assertEquals("/access/v1/search/action", pdp.requests().get(1).path());
    }

    /** Collects what a publisher signals; completes {@link #done} on completion or error. */
    static final class Collector<T> implements Flow.Subscriber<T> {

        final CompletableFuture<Void> done = new CompletableFuture<>();
        private final long initialDemand;
        private final List<T> results = new ArrayList<>();
        volatile Flow.Subscription subscription;

        Collector(long initialDemand) {
            this.initialDemand = initialDemand;
        }

        void request(long n) {
            subscription.request(n);
        }

        synchronized List<T> results() {
            return List.copyOf(results);
        }

        void awaitResults(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (results().size() < count && System.nanoTime() < deadline) {
                synchronized (this) {
                    wait(10);
                }
            }
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (initialDemand > 0) {
                subscription.request(initialDemand);
            }
        }

        @Override
        public synchronized void onNext(T item) {
            results.add(item);
            notifyAll();
        }

        @Override
        public void onError(Throwable throwable) {
            done.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            done.complete(null);
        }
    }
}
