package io.github.authzenenforcer.core.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

class CredentialWaitsTest {

    private final CredentialWaits waits = new CredentialWaits();

    @Test
    void registersOneCallbackPerStageHoweverManyRequestsGiveUp() {
        CompletableFuture<String> shared = new CompletableFuture<>();

        for (int i = 0; i < 1000; i++) {
            waits.waitFor(shared).completeExceptionally(new TimeoutException());
        }

        assertEquals(1, shared.getNumberOfDependents());
        assertEquals(1, waits.pendingStages());
    }

    @Test
    void completesEveryRequestStillWaiting() {
        CompletableFuture<String> shared = new CompletableFuture<>();
        CompletableFuture<Object> first = waits.waitFor(shared);
        CompletableFuture<Object> gaveUp = waits.waitFor(shared);
        CompletableFuture<Object> second = waits.waitFor(shared);
        gaveUp.completeExceptionally(new TimeoutException());

        shared.complete("token");

        assertEquals("token", first.join());
        assertEquals("token", second.join());
        assertTrue(gaveUp.isCompletedExceptionally());
        assertEquals(0, waits.pendingStages());
    }

    @Test
    void passesTheFailureOfTheStage() {
        CompletableFuture<String> shared = new CompletableFuture<>();
        CompletableFuture<Object> waiter = waits.waitFor(shared);
        IllegalStateException failure = new IllegalStateException("token endpoint down");

        shared.completeExceptionally(failure);

        CompletionException e = assertThrows(CompletionException.class, waiter::join);
        assertSame(failure, e.getCause());
    }

    @Test
    void waitsAgainForAStageThatCompletedEarlier() {
        CompletableFuture<String> stage = new CompletableFuture<>();
        waits.waitFor(stage);
        stage.complete("token");

        assertEquals("token", waits.waitFor(stage).join());
        assertEquals(0, waits.pendingStages());
    }

    @Test
    void keepsTheStagesOfDifferentRequestsApart() {
        CompletableFuture<String> one = new CompletableFuture<>();
        CompletableFuture<String> other = new CompletableFuture<>();
        CompletableFuture<Object> waitingForOne = waits.waitFor(one);
        CompletableFuture<Object> waitingForOther = waits.waitFor(other);

        other.complete("other");

        assertEquals("other", waitingForOther.join());
        assertFalse(waitingForOne.isDone());
    }

    @Test
    void forgetsAStageNothingElseReferences() throws InterruptedException {
        // The waiters stay reachable, as a caller may keep them; they must not keep their stages
        List<CompletableFuture<Object>> waiters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            CompletableFuture<Object> waiter = waits.waitFor(new CompletableFuture<String>());
            waiter.completeExceptionally(new TimeoutException());
            waiters.add(waiter);
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (waits.pendingStages() > 0 && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(10);
        }

        assertEquals(0, waits.pendingStages(), "a stage only the waits referenced must not stay");
    }
}
