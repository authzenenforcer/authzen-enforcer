package io.github.authzenenforcer.core.internal.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The future of a call, which its caller may cancel but not complete.
 *
 * <p>Cancelling it, or the future that {@link #toCompletableFuture()} returns, stops the call: no
 * further step starts and the step in progress is cancelled. Reactive libraries cancel it that way
 * when their subscriber cancels. The methods that would complete it throw
 * {@link UnsupportedOperationException}, as those of {@link #minimalCompletionStage()} do; dependent
 * stages are ordinary futures.
 *
 * @param <T> the type of the result
 */
public final class CallResult<T> extends CompletableFuture<T> {

    private static final Runnable NONE = () -> { };

    // Dropped once the call has ended, so that a caller who keeps this future keeps nothing of the call
    private volatile Runnable onCancel;

    /**
     * Creates the future of a call.
     *
     * @param onCancel stops the call; runs at most once, on the first cancel before the call ends
     */
    public CallResult(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    /**
     * Completes the call with its result.
     *
     * @param value the result
     * @return whether this completed the future
     */
    public boolean succeed(T value) {
        boolean completed = super.complete(value);
        onCancel = NONE;
        return completed;
    }

    /**
     * Completes the call with its failure.
     *
     * @param failure the failure
     * @return whether this completed the future
     */
    public boolean fail(Throwable failure) {
        boolean completed = super.completeExceptionally(failure);
        onCancel = NONE;
        return completed;
    }

    // Whether the caller interrupts makes no difference: the call stops either way
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        // cancel() also answers true for a future cancelled earlier; only the first one stops the call
        boolean done = isDone();
        boolean cancelled = super.cancel(mayInterruptIfRunning);
        if (cancelled && !done) {
            Runnable hook = onCancel;
            onCancel = NONE;
            hook.run();
        }
        return cancelled;
    }

    /**
     * Returns a new future completed with the result of the call. Completing it affects only that
     * future; cancelling it cancels the call.
     */
    @Override
    public CompletableFuture<T> toCompletableFuture() {
        CompletableFuture<T> copy = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                CallResult.this.cancel(mayInterruptIfRunning);
                return cancelled;
            }
        };
        whenComplete((value, error) -> {
            if (error != null) {
                copy.completeExceptionally(error instanceof CompletionException && error.getCause() != null
                        ? error.getCause()
                        : error);
            } else {
                copy.complete(value);
            }
        });
        return copy;
    }

    @Override
    public boolean complete(T value) {
        throw refused();
    }

    @Override
    public boolean completeExceptionally(Throwable ex) {
        throw refused();
    }

    @Override
    public void obtrudeValue(T value) {
        throw refused();
    }

    @Override
    public void obtrudeException(Throwable ex) {
        throw refused();
    }

    @Override
    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
        throw refused();
    }

    @Override
    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
        throw refused();
    }

    @Override
    public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
        throw refused();
    }

    @Override
    public CompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
        throw refused();
    }

    private static UnsupportedOperationException refused() {
        return new UnsupportedOperationException(
                "the SDK completes this future; cancel it or use toCompletableFuture() for a copy");
    }
}
