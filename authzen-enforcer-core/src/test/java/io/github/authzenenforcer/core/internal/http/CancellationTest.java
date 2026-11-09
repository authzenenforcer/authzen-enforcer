package io.github.authzenenforcer.core.internal.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

class CancellationTest {

    private final Cancellation cancellation = new Cancellation();

    @Test
    void cancelsTheCurrentStep() {
        CompletableFuture<Object> earlier = cancellation.track(new CompletableFuture<>());
        CompletableFuture<Object> current = cancellation.track(new CompletableFuture<>());

        cancellation.cancel();

        assertTrue(cancellation.isCancelled());
        assertTrue(current.isCancelled());
        assertFalse(earlier.isCancelled(), "a step that was replaced has already ended");
    }

    @Test
    void cancelsAStepThatStartsAfterTheCancel() {
        cancellation.cancel();

        CompletableFuture<Object> step = new CompletableFuture<>();
        assertSame(step, cancellation.track(step));

        assertTrue(step.isCancelled());
    }

    @Test
    void forgetsTheStepsOfARequestThatEnded() {
        CompletableFuture<Object> last = cancellation.track(new CompletableFuture<>());
        cancellation.release();

        cancellation.cancel();

        assertFalse(last.isCancelled(), "an ended request holds no step");
        assertTrue(cancellation.track(new CompletableFuture<>()).isCancelled(),
                "a step started after the cancel is still cancelled");
    }

    @Test
    void leavesStepsAloneWithoutACancel() {
        CompletableFuture<Object> step = cancellation.track(new CompletableFuture<>());

        assertFalse(cancellation.isCancelled());
        assertFalse(step.isCancelled());
    }
}
