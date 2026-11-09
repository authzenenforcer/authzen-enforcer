package io.github.authzenenforcer.core.internal.http;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Waits for the credential stages of a provider.
 *
 * <p>A provider may hand the same stage to every request while a single token refresh is in
 * progress. A stage offers no way to remove a callback, so a callback per request would stay on a
 * stage that never completes long after its request gave up. Instead, each stage gets at most one
 * callback, which completes the requests waiting for it at that moment; a request that stops
 * waiting only leaves that set. Stages are referenced weakly, so one that nothing else references
 * any more is forgotten.
 *
 * <p>Instances are thread-safe.
 */
final class CredentialWaits {

    private final ReentrantLock lock = new ReentrantLock();
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    // Guarded by lock
    private final Map<StageKey, Waiters> waiting = new HashMap<>();

    /**
     * Returns a future completed with the outcome of the stage. Completing the returned future
     * otherwise, for example by a timeout, stops waiting for the stage.
     *
     * @param stage the provider's stage
     * @return a future completed with the stage's value or failure
     */
    CompletableFuture<Object> waitFor(CompletionStage<?> stage) {
        CompletableFuture<Object> waiter = new CompletableFuture<>();
        Waiters waiters;
        boolean first;
        lock.lock();
        try {
            expungeCollected();
            waiters = waiting.get(new StageKey(stage, null));
            first = waiters == null;
            if (first) {
                waiters = new Waiters(new StageKey(stage, collected));
                waiting.put(waiters.key, waiters);
            }
            waiters.futures.add(waiter);
        } finally {
            lock.unlock();
        }
        // Neither callback may reference the stage, or its weak key would never be cleared
        Waiters registered = waiters;
        if (first) {
            stage.whenComplete((value, error) -> completed(registered, value, error));
        }
        waiter.whenComplete((value, error) -> stopWaiting(registered, waiter));
        return waiter;
    }

    /** Returns the number of stages with a callback, for tests. */
    int pendingStages() {
        lock.lock();
        try {
            expungeCollected();
            return waiting.size();
        } finally {
            lock.unlock();
        }
    }

    private void completed(Waiters waiters, Object value, Throwable error) {
        List<CompletableFuture<Object>> futures;
        lock.lock();
        try {
            waiting.remove(waiters.key);
            futures = new ArrayList<>(waiters.futures);
            waiters.futures.clear();
        } finally {
            lock.unlock();
        }
        // Completed outside the lock, since completing runs the waiters' continuations
        for (CompletableFuture<Object> future : futures) {
            if (error != null) {
                future.completeExceptionally(error);
            } else {
                future.complete(value);
            }
        }
    }

    // The entry stays while the stage is pending, so that later requests reuse its callback
    private void stopWaiting(Waiters waiters, CompletableFuture<Object> waiter) {
        lock.lock();
        try {
            waiters.futures.remove(waiter);
        } finally {
            lock.unlock();
        }
    }

    // Guarded by lock
    private void expungeCollected() {
        for (Object cleared = collected.poll(); cleared != null; cleared = collected.poll()) {
            waiting.remove(cleared);
        }
    }

    /** The requests waiting for one stage. */
    private static final class Waiters {

        final StageKey key;
        // Guarded by the lock of the enclosing instance
        final Set<CompletableFuture<Object>> futures = new HashSet<>();

        Waiters(StageKey key) {
            this.key = key;
        }
    }

    /** Identifies a stage by identity, without keeping it reachable. */
    private static final class StageKey extends WeakReference<Object> {

        private final int hash;

        StageKey(Object stage, ReferenceQueue<Object> queue) {
            super(stage, queue);
            this.hash = System.identityHashCode(stage);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StageKey key)) {
                return false;
            }
            Object stage = get();
            return stage != null && stage == key.get();
        }
    }
}
