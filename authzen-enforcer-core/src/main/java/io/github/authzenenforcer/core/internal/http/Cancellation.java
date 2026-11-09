package io.github.authzenenforcer.core.internal.http;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lets the caller stop a request. Once cancelled, the transport starts no further step, such as
 * waiting for a credential, an HTTP exchange or a retry, and the step in progress is cancelled.
 *
 * <p>Instances are thread-safe.
 */
public final class Cancellation {

    private volatile boolean cancelled;
    private final AtomicReference<Future<?>> current = new AtomicReference<>();

    /** Cancels the request: no further step starts, and the current one is cancelled. */
    public void cancel() {
        cancelled = true;
        Future<?> step = current.getAndSet(null);
        if (step != null) {
            step.cancel(true);
        }
    }

    /**
     * Returns whether the request was cancelled.
     *
     * @return {@code true} once {@link #cancel()} was called
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Makes the step the one that a cancel stops, and cancels it at once if the request is
     * already cancelled.
     *
     * @param step the step that starts
     * @param <T>  the type of the step
     * @return the step
     */
    <T extends Future<?>> T track(T step) {
        current.set(step);
        // A cancel between the two lines above found no step or the previous one
        if (cancelled) {
            step.cancel(true);
        }
        return step;
    }
}
