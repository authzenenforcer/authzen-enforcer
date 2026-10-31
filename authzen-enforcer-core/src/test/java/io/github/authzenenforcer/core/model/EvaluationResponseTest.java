package io.github.authzenenforcer.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class EvaluationResponseTest {

    @Test
    void createsResponseWithoutContext() {
        assertTrue(EvaluationResponse.of(true).getDecision());
        assertFalse(EvaluationResponse.of(false).getDecision());
        assertTrue(EvaluationResponse.of(true).getContext().isEmpty());
    }

    @Test
    void createsResponseWithContext() {
        Context context = Context.of(Map.of("reason_user", Map.of("403", "Insufficient privileges")));
        EvaluationResponse response = EvaluationResponse.of(false, context);

        assertFalse(response.getDecision());
        assertEquals(Optional.of(context), response.getContext());
    }

    @Test
    void rejectsNullContext() {
        assertThrows(NullPointerException.class, () -> EvaluationResponse.of(true, null));
    }

    @Test
    void implementsValueEquality() {
        assertEquals(EvaluationResponse.of(true), EvaluationResponse.of(true));
        assertNotEquals(EvaluationResponse.of(true), EvaluationResponse.of(false));
        assertNotEquals(EvaluationResponse.of(true), EvaluationResponse.of(true, Context.empty()));
        assertEquals(EvaluationResponse.of(true, Context.empty()).hashCode(),
                EvaluationResponse.of(true, Context.empty()).hashCode());
    }
}
