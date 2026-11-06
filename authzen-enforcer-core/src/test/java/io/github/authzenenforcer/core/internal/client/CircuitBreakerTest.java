package io.github.authzenenforcer.core.internal.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.client.CircuitBreakerOptions;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private static final Duration OPEN = Duration.ofSeconds(10);
    private static final Duration MAX_OPEN = Duration.ofSeconds(35);
    private static final CircuitBreakerOptions OPTIONS = CircuitBreakerOptions.builder()
            .failureThreshold(3).openDuration(OPEN).maxOpenDuration(MAX_OPEN).build();
    private static final PdpCommunicationException FAILURE = new PdpCommunicationException("PDP returned HTTP 503");

    // Near the overflow point, so that the deadline arithmetic must be wrap-safe
    private long now = Long.MAX_VALUE - Duration.ofSeconds(5).toNanos();
    // Jitter of 1 keeps the full nominal duration, so that the expected deadlines are exact
    private double jitter = 0.999_999_999_999;

    private CircuitBreaker breaker() {
        return new CircuitBreaker("PDP authorization API", OPTIONS, () -> jitter);
    }

    private void advance(Duration duration) {
        now += duration.toNanos();
    }

    private void fail(CircuitBreaker breaker, int times) {
        for (int i = 0; i < times; i++) {
            breaker.failed(breaker.acquire(now), now, FAILURE).run();
        }
    }

    private void assertOpen(CircuitBreaker breaker) {
        assertThrows(PdpCommunicationException.class, () -> breaker.acquire(now));
    }

    @Test
    void staysClosedBelowTheThreshold() {
        CircuitBreaker breaker = breaker();

        fail(breaker, 2);

        assertDoesNotThrow(() -> breaker.acquire(now));
    }

    @Test
    void opensAtTheThresholdAndCarriesTheLastFailure() {
        CircuitBreaker breaker = breaker();

        fail(breaker, 3);

        PdpCommunicationException e = assertThrows(PdpCommunicationException.class, () -> breaker.acquire(now));
        assertSame(FAILURE, e.getCause());
        assertTrue(e.getMessage().startsWith("PDP authorization API is suspended"), e.getMessage());
    }

    @Test
    void countsOnlyFailuresInARow() {
        CircuitBreaker breaker = breaker();

        fail(breaker, 2);
        breaker.succeeded(breaker.acquire(now)).run();
        fail(breaker, 2);

        assertDoesNotThrow(() -> breaker.acquire(now));
    }

    @Test
    void letsOneProbeThroughAfterTheOpenDuration() {
        CircuitBreaker breaker = breaker();
        fail(breaker, 3);
        advance(OPEN.minusNanos(1_000_000));
        assertOpen(breaker);
        advance(Duration.ofMillis(1));

        CircuitBreaker.Permit probe = breaker.acquire(now);

        assertTrue(probe.probe());
        assertOpen(breaker);
    }

    @Test
    void marksOnlyThePermitThatTurnedTheBreakerHalfOpen() {
        CircuitBreaker breaker = breaker();
        assertFalse(breaker.acquire(now).halfOpened());
        fail(breaker, 3);
        advance(OPEN);

        CircuitBreaker.Permit probe = breaker.acquire(now);
        assertTrue(probe.halfOpened());
        breaker.released(probe);

        // Still half-open: the next probe did not cause the transition
        CircuitBreaker.Permit next = breaker.acquire(now);
        assertTrue(next.probe());
        assertFalse(next.halfOpened());
    }

    @Test
    void closesWhenTheProbeSucceeds() {
        CircuitBreaker breaker = breaker();
        fail(breaker, 3);
        advance(OPEN);

        breaker.succeeded(breaker.acquire(now)).run();

        assertDoesNotThrow(() -> breaker.acquire(now));
        fail(breaker, 2);
        assertDoesNotThrow(() -> breaker.acquire(now), "the failure count starts over after recovery");
    }

    @Test
    void doublesTheOpenDurationAfterAFailedProbeUpToTheMaximum() {
        CircuitBreaker breaker = breaker();
        fail(breaker, 3);

        // 10 s, then 20 s, then capped at 35 s instead of 40 s
        for (Duration expected : new Duration[] {OPEN, Duration.ofSeconds(20), MAX_OPEN, MAX_OPEN}) {
            advance(expected.minusMillis(1));
            assertOpen(breaker);
            advance(Duration.ofMillis(1));
            CircuitBreaker.Permit probe = breaker.acquire(now);
            breaker.failed(probe, now, FAILURE).run();
        }
    }

    @Test
    void neverOpensForLessThanHalfTheNominalDuration() {
        jitter = 0;
        CircuitBreaker breaker = breaker();
        fail(breaker, 3);

        advance(OPEN.dividedBy(2).minusNanos(1));
        assertOpen(breaker);
        advance(Duration.ofNanos(1));

        assertDoesNotThrow(() -> breaker.acquire(now));
    }

    @Test
    void releasingTheProbeLetsTheNextCallProbe() {
        CircuitBreaker breaker = breaker();
        fail(breaker, 3);
        advance(OPEN);

        breaker.released(breaker.acquire(now));

        assertTrue(breaker.acquire(now).probe());
    }

    @Test
    void ignoresLateResultsOfCallsAdmittedBeforeItOpened() {
        CircuitBreaker breaker = breaker();
        CircuitBreaker.Permit early = breaker.acquire(now);
        fail(breaker, 3);

        breaker.succeeded(early).run();
        assertOpen(breaker);

        advance(OPEN);
        CircuitBreaker.Permit probe = breaker.acquire(now);
        breaker.failed(early, now, FAILURE).run();
        breaker.succeeded(probe).run();
        assertDoesNotThrow(() -> breaker.acquire(now), "a late failure must not reopen or extend");
    }

    @Test
    void survivesAFailureWhoseMessageThrows() {
        CircuitBreaker breaker = breaker();
        RuntimeException hostile = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new IllegalStateException("broken exception type");
            }
        };

        for (int i = 0; i < 3; i++) {
            Runnable log = assertDoesNotThrow(() -> breaker.failed(breaker.acquire(now), now, hostile));
            assertDoesNotThrow(log::run);
        }

        PdpCommunicationException e = assertThrows(PdpCommunicationException.class, () -> breaker.acquire(now));
        assertSame(hostile, e.getCause());
    }

    @Test
    void neverRejectsWhenDisabled() {
        CircuitBreaker breaker = new CircuitBreaker("PDP authorization API", CircuitBreakerOptions.disabled(), () -> jitter);

        fail(breaker, 50);

        assertDoesNotThrow(() -> breaker.acquire(now));
    }
}
