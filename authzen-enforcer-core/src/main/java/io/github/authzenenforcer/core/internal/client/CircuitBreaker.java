package io.github.authzenenforcer.core.internal.client;

import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.client.CircuitBreakerOptions;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleSupplier;

/**
 * Stops calls to a PDP that keeps failing.
 *
 * <p>Closed, it lets every call through and counts failures in a row. At the threshold it opens for
 * a randomized duration between half and all of the nominal one, which doubles with each failed
 * probe up to the maximum. Open, it rejects calls. When the duration has passed it lets exactly
 * one call through as a probe and rejects the others; the probe's success closes it, its failure
 * opens it again. Results of calls that were let through before the breaker last opened are
 * ignored, so that a late answer cannot close or extend the current open period.
 *
 * <p>Callers pass the time, so that a failing clock is handled where the caller can fail its own
 * request, and they run the returned log action after completing their futures, so that a slow or
 * failing logger can neither leave anyone waiting nor change a result. Messages are built when
 * the log action runs, never under the lock.
 *
 * <p>Instances are thread-safe.
 */
final class CircuitBreaker {

    /** Admission to call the PDP. */
    record Permit(long generation, boolean probe) {
    }

    private static final System.Logger LOGGER = System.getLogger(CircuitBreaker.class.getName());
    private static final Permit UNLIMITED = new Permit(-1, false);
    private static final Runnable NO_LOG = () -> { };

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final boolean enabled;
    private final int threshold;
    private final long openNanos;
    private final long maxOpenNanos;
    private final DoubleSupplier random;
    private final ReentrantLock lock = new ReentrantLock();

    // Guarded by lock
    private State state = State.CLOSED;
    private int failures;
    private int opens;
    private long openUntilNanos;
    private boolean probing;
    private Throwable lastFailure;
    private long generation;

    /**
     * Creates a breaker.
     *
     * @param name    what the breaker protects, for messages, such as {@code "PDP metadata discovery"}
     * @param options the options; disabled options let every call through
     * @param random  uniform values in {@code [0, 1)} for the jitter
     */
    CircuitBreaker(String name, CircuitBreakerOptions options, DoubleSupplier random) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.enabled = options.isEnabled();
        this.threshold = options.getFailureThreshold();
        this.openNanos = options.getOpenDuration().toNanos();
        this.maxOpenNanos = options.getMaxOpenDuration().toNanos();
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /**
     * Asks to call the PDP.
     *
     * @param now the current time on a monotonic nanosecond clock
     * @return the permit, which the caller hands back with exactly one of the result methods
     * @throws PdpCommunicationException if the breaker is open or a probe is already running; its
     *                                   cause is the last failure
     */
    Permit acquire(long now) {
        if (!enabled) {
            return UNLIMITED;
        }
        lock.lock();
        try {
            if (state == State.OPEN) {
                if (now - openUntilNanos < 0) {
                    throw rejection("next attempt in " + Duration.ofNanos(openUntilNanos - now).toMillis() + " ms");
                }
                state = State.HALF_OPEN;
                probing = false;
            }
            if (state == State.HALF_OPEN) {
                if (probing) {
                    throw rejection("a probe is in progress");
                }
                probing = true;
                return new Permit(generation, true);
            }
            return new Permit(generation, false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records that the PDP answered.
     *
     * @return the log action to run after the caller completed its futures
     */
    Runnable succeeded(Permit permit) {
        if (permit == UNLIMITED) {
            return NO_LOG;
        }
        lock.lock();
        try {
            if (permit.generation() != generation) {
                return NO_LOG;
            }
            boolean wasOpen = state != State.CLOSED;
            state = State.CLOSED;
            failures = 0;
            opens = 0;
            probing = false;
            lastFailure = null;
            return wasOpen ? () -> LOGGER.log(Level.INFO, name + " recovered; the circuit breaker is closed again")
                    : NO_LOG;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a failure that counts towards opening the breaker.
     *
     * @param now   the current time on the same clock as {@link #acquire(long)}
     * @param cause the failure
     * @return the log action to run after the caller completed its futures
     */
    Runnable failed(Permit permit, long now, Throwable cause) {
        if (permit == UNLIMITED) {
            return NO_LOG;
        }
        lock.lock();
        try {
            if (permit.generation() != generation) {
                return NO_LOG;
            }
            lastFailure = cause;
            if (state == State.HALF_OPEN && permit.probe()) {
                return open(now);
            }
            if (state == State.CLOSED && ++failures >= threshold) {
                return open(now);
            }
            return NO_LOG;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a call whose outcome says nothing about the PDP, such as a failing credential provider
     * or a cancelled request. A probe is released, so that the next call can probe.
     */
    void released(Permit permit) {
        if (permit == UNLIMITED) {
            return;
        }
        lock.lock();
        try {
            if (permit.generation() == generation && permit.probe()) {
                probing = false;
            }
        } finally {
            lock.unlock();
        }
    }

    // Called with the lock held
    private Runnable open(long now) {
        opens++;
        long nominal = openNanos;
        for (int i = 1; i < opens && nominal < maxOpenNanos; i++) {
            nominal *= 2;
        }
        nominal = Math.min(nominal, maxOpenNanos);
        // Equal jitter: never shorter than half the nominal duration
        long duration = nominal / 2 + (long) (random.getAsDouble() * (nominal - nominal / 2));
        openUntilNanos = now + duration;
        state = State.OPEN;
        probing = false;
        failures = 0;
        generation++;
        // The message is built when the log action runs, outside the lock
        boolean first = opens == 1;
        Throwable failure = lastFailure;
        return () -> LOGGER.log(Level.WARNING, () -> name + " failed "
                + (first ? threshold + " times in a row" : "again after recovery probe")
                + "; the circuit breaker is open for " + Duration.ofNanos(duration).toMillis() + " ms: "
                + describe(failure));
    }

    private PdpCommunicationException rejection(String detail) {
        return new PdpCommunicationException(name + " is suspended because the PDP kept failing (" + detail
                + "): " + describe(lastFailure), lastFailure);
    }

    // Exception types from integrator code may misbehave; a message must never fail a caller
    private static String describe(Throwable failure) {
        if (failure == null) {
            return "no failure recorded";
        }
        try {
            return String.valueOf(failure.getMessage());
        } catch (RuntimeException e) {
            return failure.getClass().getName();
        }
    }
}
