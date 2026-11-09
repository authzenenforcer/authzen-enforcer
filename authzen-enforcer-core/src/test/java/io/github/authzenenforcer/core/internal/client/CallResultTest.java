package io.github.authzenenforcer.core.internal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CallResultTest {

    private final AtomicInteger cancels = new AtomicInteger();
    private final CallResult<String> result = new CallResult<>(cancels::incrementAndGet);

    @Test
    void refusesToBeCompletedByItsHolder() {
        assertThrows(UnsupportedOperationException.class, () -> result.complete("forged"));
        assertThrows(UnsupportedOperationException.class, () -> result.completeExceptionally(new IllegalStateException()));
        assertThrows(UnsupportedOperationException.class, () -> result.obtrudeValue("forged"));
        assertThrows(UnsupportedOperationException.class, () -> result.obtrudeException(new IllegalStateException()));
        assertThrows(UnsupportedOperationException.class, () -> result.completeAsync(() -> "forged"));
        assertThrows(UnsupportedOperationException.class, () -> result.orTimeout(1, TimeUnit.SECONDS));
        assertThrows(UnsupportedOperationException.class, () -> result.completeOnTimeout("forged", 1, TimeUnit.SECONDS));
        assertFalse(result.isDone());
    }

    @Test
    void cancelsTheCallOnce() {
        assertTrue(result.cancel(false));
        assertTrue(result.cancel(true), "a cancelled future stays cancelled");

        assertTrue(result.isCancelled());
        assertEquals(1, cancels.get());
        assertFalse(result.succeed("late"), "a result that arrives after the cancel is dropped");
    }

    @Test
    void doesNotCancelACompletedCall() {
        result.succeed("done");

        assertFalse(result.cancel(true));
        assertEquals(0, cancels.get());
    }

    @Test
    void cancelsTheCallThroughItsCopy() {
        CompletableFuture<String> copy = result.toCompletableFuture();

        assertTrue(copy.cancel(false));

        assertTrue(result.isCancelled());
        assertEquals(1, cancels.get());
    }

    @Test
    void completingTheCopyLeavesTheCallAlone() {
        CompletableFuture<String> copy = result.toCompletableFuture();

        copy.complete("forged");
        result.succeed("real");

        assertEquals("real", result.join());
        assertEquals(0, cancels.get());
    }

    @Test
    void passesTheOutcomeToTheCopy() {
        CompletableFuture<String> copy = result.toCompletableFuture();
        IllegalStateException failure = new IllegalStateException("failed");

        result.fail(failure);

        assertTrue(copy.isCompletedExceptionally());
        assertEquals(failure, assertThrows(java.util.concurrent.CompletionException.class, copy::join).getCause());
    }

    @Test
    void dependentStagesAreOrdinaryFutures() {
        CompletableFuture<Integer> length = result.thenApply(String::length);

        assertTrue(length.complete(3), "a dependent stage is not the SDK's future");
    }
}
