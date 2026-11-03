package io.github.authzenenforcer.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class EvaluationsResponseTest {

    private static final EvaluationResponse PERMIT = EvaluationResponse.of(true);
    private static final EvaluationResponse DENY = EvaluationResponse.of(false);

    @Test
    void answersPerItemAndForTheWholeBatch() {
        EvaluationsResponse response = EvaluationsResponse.of(List.of(PERMIT, DENY, PERMIT), 3);

        assertTrue(response.isPermitted(0));
        assertFalse(response.isPermitted(1));
        assertFalse(response.allPermitted());
        assertTrue(response.anyPermitted());
    }

    @Test
    void treatsItemsThePdpDidNotReturnAsDenies() {
        // deny_on_first_deny stopped at the second of three items
        EvaluationsResponse response = EvaluationsResponse.of(List.of(PERMIT, DENY), 3);

        assertEquals(Optional.empty(), response.get(2));
        assertFalse(response.isPermitted(2));
        assertFalse(response.allPermitted());
    }

    @Test
    void neverReportsAllPermittedForAShortAnswer() {
        assertFalse(EvaluationsResponse.of(List.of(PERMIT, PERMIT), 3).allPermitted());
        assertFalse(EvaluationsResponse.of(List.of(), 2).anyPermitted());
        assertTrue(EvaluationsResponse.of(List.of(PERMIT, PERMIT), 2).allPermitted());
    }

    @Test
    void rejectsIndexesOutsideTheRequest() {
        EvaluationsResponse response = EvaluationsResponse.of(List.of(PERMIT), 2);

        assertThrows(IndexOutOfBoundsException.class, () -> response.isPermitted(2));
        assertThrows(IndexOutOfBoundsException.class, () -> response.get(-1));
    }

    @Test
    void rejectsMoreDecisionsThanRequestedItems() {
        assertThrows(IllegalArgumentException.class, () -> EvaluationsResponse.of(List.of(PERMIT, PERMIT), 1));
    }
}
