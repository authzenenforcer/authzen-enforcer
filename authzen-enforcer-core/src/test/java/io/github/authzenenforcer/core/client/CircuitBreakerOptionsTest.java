package io.github.authzenenforcer.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class CircuitBreakerOptionsTest {

    @Test
    void hasTheDocumentedDefaults() {
        CircuitBreakerOptions options = CircuitBreakerOptions.defaults();

        assertTrue(options.isEnabled());
        assertEquals(3, options.getFailureThreshold());
        assertEquals(Duration.ofSeconds(5), options.getOpenDuration());
        assertEquals(Duration.ofMinutes(5), options.getMaxOpenDuration());
        assertEquals(options, CircuitBreakerOptions.builder().build());
    }

    @Test
    void canBeDisabled() {
        CircuitBreakerOptions options = CircuitBreakerOptions.disabled();

        assertFalse(options.isEnabled());
        assertNotEquals(CircuitBreakerOptions.defaults(), options);
        assertEquals("CircuitBreakerOptions{disabled}", options.toString());
    }

    @Test
    void buildsCustomOptions() {
        CircuitBreakerOptions options = CircuitBreakerOptions.builder()
                .failureThreshold(1)
                .openDuration(Duration.ofMillis(100))
                .maxOpenDuration(Duration.ofDays(1))
                .build();

        assertEquals(1, options.getFailureThreshold());
        assertEquals(Duration.ofMillis(100), options.getOpenDuration());
        assertEquals(Duration.ofDays(1), options.getMaxOpenDuration());
        assertEquals("CircuitBreakerOptions{failureThreshold=1, openDuration=PT0.1S, maxOpenDuration=PT24H}",
                options.toString());
    }

    @Test
    void rejectsOutOfRangeValues() {
        CircuitBreakerOptions.Builder builder = CircuitBreakerOptions.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.failureThreshold(0));
        assertThrows(IllegalArgumentException.class, () -> builder.failureThreshold(101));
        assertThrows(IllegalArgumentException.class, () -> builder.openDuration(Duration.ofMillis(99)));
        assertThrows(IllegalArgumentException.class, () -> builder.maxOpenDuration(Duration.ofDays(1).plusNanos(1)));
        NullPointerException e = assertThrows(NullPointerException.class, () -> builder.openDuration(null));
        assertEquals("openDuration must not be null", e.getMessage());
    }

    @Test
    void rejectsAnOpenDurationLongerThanTheMaximum() {
        CircuitBreakerOptions.Builder builder = CircuitBreakerOptions.builder()
                .openDuration(Duration.ofMinutes(10)).maxOpenDuration(Duration.ofMinutes(5));

        assertThrows(IllegalStateException.class, builder::build);
    }
}
