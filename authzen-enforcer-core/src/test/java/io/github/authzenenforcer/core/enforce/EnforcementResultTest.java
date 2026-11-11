package io.github.authzenenforcer.core.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.enforce.EnforcementResult.Outcome;

import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class EnforcementResultTest {

    @Test
    void refusesForAFrameworkIntegration() {
        EnforcementResult result = EnforcementResult.refused("The resourceId expression failed");

        assertEquals(Outcome.REFUSED, result.getOutcome());
        assertFalse(result.isAllowed());
        assertEquals(OptionalInt.of(EnforcementResult.FORBIDDEN), result.getStatus());
        assertEquals(Optional.of("The resourceId expression failed"), result.getReason());
        assertTrue(result.getRequest().isEmpty());
        assertTrue(result.getFailure().isEmpty());
    }

    @Test
    void requiresAReason() {
        assertThrows(NullPointerException.class, () -> EnforcementResult.refused(null));
    }
}
