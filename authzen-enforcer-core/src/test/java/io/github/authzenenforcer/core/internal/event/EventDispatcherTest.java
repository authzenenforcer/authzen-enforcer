package io.github.authzenenforcer.core.internal.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.event.AttemptCompleted;
import io.github.authzenenforcer.core.internal.client.TestLoggerFinder;
import io.github.authzenenforcer.core.event.BreakerTransition;
import io.github.authzenenforcer.core.event.CallCompleted;
import io.github.authzenenforcer.core.event.EventContent;
import io.github.authzenenforcer.core.event.MetadataRefresh;
import io.github.authzenenforcer.core.event.Operation;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.spi.PepListener;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class EventDispatcherTest {

    private static final URI ENDPOINT = URI.create("https://pdp.example.com/access/v1/evaluation");
    private static final int CAPACITY = 20;
    private static final long MAX_BYTES = 1L << 30;

    /** Runs tasks only when told, so that tests decide when queues drain. */
    private static final class ManualExecutor implements Executor {

        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean rejecting;

        @Override
        public void execute(Runnable task) {
            if (rejecting) {
                throw new RejectedExecutionException("rejected");
            }
            tasks.add(task);
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }

    /** Records the request ids of attempts and the outcomes of calls. */
    private static final class Recorder implements PepListener {

        final List<String> seen = new ArrayList<>();

        @Override
        public void onCallCompleted(CallCompleted<?, ?> event) {
            seen.add("call");
        }

        @Override
        public void onAttemptCompleted(AttemptCompleted event) {
            seen.add(event.getRequestId());
        }

        @Override
        public void onBreakerTransition(BreakerTransition event) {
            seen.add("breaker");
        }

        @Override
        public void onMetadataRefresh(MetadataRefresh event) {
            seen.add(event.getResult().name());
        }
    }

    private final ManualExecutor executor = new ManualExecutor();
    private final Recorder recorder = new Recorder();

    private EventDispatcher dispatcher(PepListener... listeners) {
        return EventDispatcher.direct(List.of(listeners), executor, CAPACITY, MAX_BYTES, EventContent.defaults(),
                System::nanoTime);
    }

    private static AttemptCompleted attempt(String requestId) {
        return AttemptCompleted.builder()
                .operation(Operation.EVALUATION)
                .endpoint(ENDPOINT)
                .method("POST")
                .requestId(requestId)
                .attempt(1)
                .status(200)
                .startTime(Instant.EPOCH)
                .duration(Duration.ofMillis(3))
                .build();
    }

    private static CallCompleted<EvaluationRequest, EvaluationResponse> call() {
        return CallCompleted.builder(Operation.EVALUATION)
                .request(EvaluationRequest.of(Subject.of("user", "alice"), Action.of("can_read"),
                        Resource.of("account", "123")))
                .response(EvaluationResponse.of(true))
                .startTime(Instant.EPOCH)
                .duration(Duration.ofMillis(3))
                .build();
    }

    private static BreakerTransition transition() {
        return BreakerTransition.builder()
                .breaker(BreakerTransition.Breaker.AUTHORIZATION_API)
                .from(BreakerTransition.State.OPEN)
                .to(BreakerTransition.State.CLOSED)
                .time(Instant.EPOCH)
                .build();
    }

    private static MetadataRefresh refresh(MetadataRefresh.Result result) {
        MetadataRefresh.Builder builder = MetadataRefresh.builder()
                .result(result)
                .location(URI.create("https://pdp.example.com/.well-known/authzen-configuration"))
                .time(Instant.EPOCH);
        if (result == MetadataRefresh.Result.FAILED) {
            builder.failure(new PdpCommunicationException("unreachable"));
        }
        if (result == MetadataRefresh.Result.FRESH) {
            builder.metadata(PdpMetadata.builder()
                    .policyDecisionPoint("https://pdp.example.com")
                    .accessEvaluationEndpoint(ENDPOINT)
                    .build());
        }
        return builder.build();
    }

    @Test
    void withoutListenersIsInactive() {
        EventDispatcher none = EventDispatcher.create(List.of(), null, CAPACITY, MAX_BYTES, EventContent.defaults());

        assertSame(EventDispatcher.NONE, none);
        assertFalse(none.isActive());
        assertSame(CallTrace.NONE, CallTrace.start(none, Operation.EVALUATION));
    }

    @Test
    void deliversInOrderOnTheExecutor() {
        EventDispatcher dispatcher = dispatcher(recorder);

        dispatcher.publish(attempt("a"));
        dispatcher.publish(attempt("b"));
        dispatcher.publish(call(), 0);
        assertTrue(recorder.seen.isEmpty());

        executor.runAll();
        assertEquals(List.of("a", "b", "call"), recorder.seen);
    }

    @Test
    void schedulesOneTaskPerListenerAtATime() {
        Recorder other = new Recorder();
        EventDispatcher dispatcher = dispatcher(recorder, other);

        for (int i = 0; i < 5; i++) {
            dispatcher.publish(attempt("a" + i));
        }

        assertEquals(2, executor.tasks.size());
        executor.runAll();
        assertEquals(5, recorder.seen.size());
        assertEquals(5, other.seen.size());
    }

    @Test
    void makesWayForOtherListenersAfterABatch() {
        EventDispatcher dispatcher = EventDispatcher.direct(List.of(recorder), executor, 1000, MAX_BYTES, EventContent.defaults(),
                System::nanoTime);
        for (int i = 0; i < EventDispatcher.BATCH + 1; i++) {
            dispatcher.publish(call(), 0);
        }

        executor.tasks.poll().run();

        assertEquals(EventDispatcher.BATCH, recorder.seen.size());
        assertEquals(1, executor.tasks.size());
        executor.runAll();
        assertEquals(EventDispatcher.BATCH + 1, recorder.seen.size());
    }

    @Test
    void dropsLowPriorityEventsFromEightyPercentOn() {
        EventDispatcher dispatcher = dispatcher(recorder);
        int lowLimit = CAPACITY * 4 / 5;
        for (int i = 0; i < lowLimit; i++) {
            dispatcher.publish(attempt("a" + i));
        }

        dispatcher.publish(attempt("dropped"));
        dispatcher.publish(refresh(MetadataRefresh.Result.FRESH), 0);
        dispatcher.publish(refresh(MetadataRefresh.Result.DEFAULT_PATHS), 0);
        dispatcher.publish(refresh(MetadataRefresh.Result.FAILED), 0);
        dispatcher.publish(transition());
        dispatcher.publish(call(), 0);

        assertEquals(3, dispatcher.dropped());
        executor.runAll();
        assertEquals(lowLimit + 3, recorder.seen.size());
        assertFalse(recorder.seen.contains("dropped"));
        assertEquals(List.of("FAILED", "breaker", "call"), recorder.seen.subList(lowLimit, lowLimit + 3));
    }

    @Test
    void dropsEveryEventWhenFull() {
        EventDispatcher dispatcher = dispatcher(recorder);
        for (int i = 0; i < CAPACITY; i++) {
            dispatcher.publish(call(), 0);
        }

        TestLoggerFinder.clearRecords();
        dispatcher.publish(call(), 0);
        dispatcher.publish(transition());

        assertEquals(2, dispatcher.dropped());
        assertTrue(TestLoggerFinder.records().stream()
                .anyMatch(r -> r.contains("PEP listener " + Recorder.class.getName() + " cannot keep up")),
                TestLoggerFinder.records()::toString);
        executor.runAll();
        assertEquals(CAPACITY, recorder.seen.size());

        // Room again after draining
        dispatcher.publish(transition());
        executor.runAll();
        assertEquals("breaker", recorder.seen.get(CAPACITY));
    }

    @Test
    void dropsAnEventThatWouldExceedTheByteBudget() {
        long budget = 10 * EventDispatcher.EVENT_OVERHEAD_BYTES;
        EventDispatcher dispatcher = EventDispatcher.direct(List.of(recorder), executor, CAPACITY, budget,
                EventContent.defaults(), System::nanoTime);

        // Each call counts six overheads, so the second one does not fit, while a small event does
        dispatcher.publish(call(), 5 * EventDispatcher.EVENT_OVERHEAD_BYTES);
        dispatcher.publish(call(), 5 * EventDispatcher.EVENT_OVERHEAD_BYTES);
        dispatcher.publish(transition());

        assertEquals(1, dispatcher.dropped());
        executor.runAll();
        assertEquals(List.of("call", "breaker"), recorder.seen);

        // Delivered events give their bytes back
        dispatcher.publish(call(), 5 * EventDispatcher.EVENT_OVERHEAD_BYTES);
        executor.runAll();
        assertEquals(List.of("call", "breaker", "call"), recorder.seen);
    }

    @Test
    void dropsLowPriorityEventsFromEightyPercentOfTheByteBudget() {
        long budget = 10 * EventDispatcher.EVENT_OVERHEAD_BYTES;
        EventDispatcher dispatcher = EventDispatcher.direct(List.of(recorder), executor, CAPACITY, budget,
                EventContent.defaults(), System::nanoTime);
        dispatcher.publish(call(), 7 * EventDispatcher.EVENT_OVERHEAD_BYTES);

        dispatcher.publish(attempt("dropped"));
        dispatcher.publish(transition());

        assertEquals(1, dispatcher.dropped());
        executor.runAll();
        assertEquals(List.of("call", "breaker"), recorder.seen);
    }

    @Test
    void neverRunsAnIntegratorExecutorOnThePublishingThread() throws InterruptedException {
        List<String> threads = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        PepListener listener = new PepListener() {
            @Override
            public void onCallCompleted(CallCompleted<?, ?> event) {
                threads.add(Thread.currentThread().getName());
                delivered.countDown();
            }
        };
        // Runs every task inline, on the thread that calls execute
        EventDispatcher dispatcher = EventDispatcher.create(List.of(listener), Runnable::run, CAPACITY, MAX_BYTES,
                EventContent.defaults());

        dispatcher.publish(call(), 0);

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("authzen-enforcer-events-handoff"), threads);
    }

    @Test
    void keepsEventsAHandedOffExecutorRejected() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(2);
        PepListener listener = new PepListener() {
            @Override
            public void onAttemptCompleted(AttemptCompleted event) {
                delivered.countDown();
            }
        };
        AtomicBoolean reject = new AtomicBoolean(true);
        Executor executor = task -> {
            if (reject.getAndSet(false)) {
                throw new RejectedExecutionException("rejected");
            }
            task.run();
        };
        EventDispatcher dispatcher = EventDispatcher.create(List.of(listener), executor, CAPACITY, MAX_BYTES,
                EventContent.defaults());

        dispatcher.publish(attempt("a"));
        // The rejection happens on the hand-off thread; wait until the queue can be scheduled again
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (delivered.getCount() == 2 && System.nanoTime() - deadline < 0) {
            dispatcher.publish(attempt("b"));
            Thread.sleep(10);
        }

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
    }

    @Test
    void warnsAboutARejectingExecutorOnceAMinute() throws InterruptedException {
        TestLoggerFinder.clearRecords();
        AtomicInteger rejections = new AtomicInteger();
        Executor shutDown = task -> {
            rejections.incrementAndGet();
            throw new RejectedExecutionException("shut down");
        };
        Executor target = EventDispatcher.handedOff(shutDown, new EventDispatcher.Throttle(() -> 0));
        EventDispatcher dispatcher = EventDispatcher.direct(List.of(recorder), target, CAPACITY, MAX_BYTES,
                EventContent.defaults(), System::nanoTime);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (rejections.get() < 5 && System.nanoTime() - deadline < 0) {
            dispatcher.publish(transition());
            Thread.sleep(5);
        }

        assertTrue(rejections.get() >= 5);
        assertEquals(1, TestLoggerFinder.records().stream().filter(r -> r.contains("rejected a delivery")).count(),
                TestLoggerFinder.records()::toString);
    }

    @Test
    void keepsDeliveringAfterAListenerFails() {
        PepListener failing = new PepListener() {
            @Override
            public void onAttemptCompleted(AttemptCompleted event) {
                throw new IllegalStateException("listener failure");
            }
        };
        EventDispatcher dispatcher = dispatcher(PepListener.composite(failing, recorder));

        dispatcher.publish(attempt("a"));
        dispatcher.publish(attempt("b"));
        executor.runAll();

        assertEquals(List.of("a", "b"), recorder.seen);
    }

    @Test
    void containsErrorsThatAFaultyListenerCauses() {
        List<String> seen = new ArrayList<>();
        PepListener failing = new PepListener() {
            @Override
            public void onAttemptCompleted(AttemptCompleted event) {
                seen.add(event.getRequestId());
                switch (event.getRequestId()) {
                    case "a" -> throw new AssertionError("listener error");
                    case "b" -> throw new NoClassDefFoundError("listener error");
                    case "c" -> throw new StackOverflowError();
                    default -> { }
                }
            }
        };
        EventDispatcher dispatcher = dispatcher(failing, PepListener.composite(failing, recorder));
        for (String id : List.of("a", "b", "c", "d")) {
            dispatcher.publish(attempt(id));
        }

        executor.runAll();

        // The listener's own queue drains first, then the composite's
        assertEquals(List.of("a", "b", "c", "d", "a", "b", "c", "d"), seen);
        assertEquals(List.of("a", "b", "c", "d"), recorder.seen);
    }

    @Test
    void keepsDeliveringAfterAnErrorItDoesNotContain() {
        List<String> seen = new ArrayList<>();
        PepListener failing = new PepListener() {
            @Override
            public void onAttemptCompleted(AttemptCompleted event) {
                seen.add(event.getRequestId());
                if (seen.size() == 1) {
                    throw new OutOfMemoryError("listener error");
                }
            }
        };
        EventDispatcher dispatcher = dispatcher(failing);
        dispatcher.publish(attempt("a"));
        dispatcher.publish(attempt("b"));

        assertThrows(OutOfMemoryError.class, () -> executor.tasks.poll().run());
        executor.runAll();

        assertEquals(List.of("a", "b"), seen);
    }

    @Test
    void keepsEventsAnExecutorRejectedForTheNextPublish() {
        EventDispatcher dispatcher = dispatcher(recorder);
        executor.rejecting = true;

        dispatcher.publish(attempt("a"));
        executor.rejecting = false;
        dispatcher.publish(attempt("b"));
        executor.runAll();

        assertEquals(List.of("a", "b"), recorder.seen);
    }

    @Test
    void aBlockedListenerHoldsUpNoOtherByDefault() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
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
        PepListener other = new PepListener() {
            @Override
            public void onCallCompleted(CallCompleted<?, ?> event) {
                delivered.countDown();
            }
        };
        EventDispatcher dispatcher = EventDispatcher.create(List.of(blocked, other), null, CAPACITY, MAX_BYTES,
                EventContent.defaults());

        try {
            dispatcher.publish(call(), 0);
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void usesADaemonThreadByDefault() throws InterruptedException {
        List<Thread> threads = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        PepListener listener = new PepListener() {
            @Override
            public void onCallCompleted(CallCompleted<?, ?> event) {
                threads.add(Thread.currentThread());
                delivered.countDown();
            }
        };
        EventDispatcher dispatcher = EventDispatcher.create(List.of(listener), null, CAPACITY, MAX_BYTES, EventContent.defaults());

        dispatcher.publish(call(), 0);

        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertTrue(threads.get(0).isDaemon());
        assertEquals("authzen-enforcer-events", threads.get(0).getName());
    }
}
