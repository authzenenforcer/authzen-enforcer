package io.github.authzenenforcer.core.internal.client;

import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.client.CircuitBreakerOptions;
import io.github.authzenenforcer.core.event.BreakerTransition;
import io.github.authzenenforcer.core.internal.event.EventDispatcher;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
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
 * the log action runs, never under the lock; the action also publishes the transition to the
 * listeners.
 *
 * <p>Instances are thread-safe.
 */
final class CircuitBreaker {

    /**
     * Admission to call the PDP.
     *
     * @param halfOpened whether acquiring this permit turned the breaker half-open, which
     *                   {@link #announce(Permit)} publishes
     */
    record Permit(long generation, boolean probe, boolean halfOpened) {
    }

    private static final System.Logger LOGGER = System.getLogger(CircuitBreaker.class.getName());
    private static final Permit UNLIMITED = new Permit(-1, false, false);
    private static final Runnable NO_LOG = () -> { };

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final BreakerTransition.Breaker kind;
    private final EventDispatcher events;
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
        this(name, BreakerTransition.Breaker.AUTHORIZATION_API, options, random, EventDispatcher.NONE);
    }

    /**
     * Creates a breaker that publishes its transitions.
     *
     * @param name    what the breaker protects, for messages, such as {@code "PDP metadata discovery"}
     * @param kind    what the breaker protects, for events
     * @param options the options; disabled options let every call through
     * @param random  uniform values in {@code [0, 1)} for the jitter
     * @param events  where transitions are published
     */
    CircuitBreaker(String name, BreakerTransition.Breaker kind, CircuitBreakerOptions options, DoubleSupplier random,
            EventDispatcher events) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
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
        Permit permit;
        boolean halfOpened = false;
        lock.lock();
        try {
            if (state == State.OPEN) {
                if (now - openUntilNanos < 0) {
                    throw rejection("next attempt in " + Duration.ofNanos(openUntilNanos - now).toMillis() + " ms");
                }
                state = State.HALF_OPEN;
                probing = false;
                halfOpened = true;
            }
            if (state == State.HALF_OPEN) {
                if (probing) {
                    throw rejection("a probe is in progress");
                }
                probing = true;
                permit = new Permit(generation, true, halfOpened);
            } else {
                permit = new Permit(generation, false, false);
            }
        } finally {
            lock.unlock();
        }
        return permit;
    }

    /**
     * Publishes the transition to half-open if acquiring the permit caused it. Callers run it
     * after releasing their own locks and before using the permit; if it throws an {@code Error},
     * they must release the permit, since a lost probe would keep the breaker rejecting every call.
     *
     * @param permit the permit {@link #acquire(long)} returned
     */
    void announce(Permit permit) {
        if (permit.halfOpened()) {
            publish(transition(State.OPEN, BreakerTransition.State.HALF_OPEN));
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
            State previous = state;
            state = State.CLOSED;
            failures = 0;
            opens = 0;
            probing = false;
            lastFailure = null;
            if (previous == State.CLOSED) {
                return NO_LOG;
            }
            return () -> {
                publish(transition(previous, BreakerTransition.State.CLOSED));
                LOGGER.log(Level.INFO, name + " recovered; the circuit breaker is closed again");
            };
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
        State previous = state;
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
        return () -> {
            // Published first: a failing logger must not cost the listeners the event
            publish(transition(previous, BreakerTransition.State.OPEN)
                    .openDuration(Duration.ofNanos(duration))
                    .failure(failure));
            LOGGER.log(Level.WARNING, () -> name + " failed "
                    + (first ? threshold + " times in a row" : "again after recovery probe")
                    + "; the circuit breaker is open for " + Duration.ofNanos(duration).toMillis() + " ms: "
                    + describe(failure));
        };
    }

    private BreakerTransition.Builder transition(State from, BreakerTransition.State to) {
        return BreakerTransition.builder()
                .breaker(kind)
                .from(BreakerTransition.State.valueOf(from.name()))
                .to(to);
    }

    private void publish(BreakerTransition.Builder transition) {
        if (events.isActive()) {
            events.publish(transition.time(Instant.now()).build());
        }
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
