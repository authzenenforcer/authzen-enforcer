package io.github.authzenenforcer.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class EvaluationRequestTest {

    private final Subject subject = Subject.of("user", "alice@example.com");
    private final Action action = Action.of("can_read");
    private final Resource resource = Resource.of("account", "123");

    @Test
    void createsRequestWithoutContext() {
        EvaluationRequest request = EvaluationRequest.of(subject, action, resource);

        assertEquals(subject, request.getSubject());
        assertEquals(action, request.getAction());
        assertEquals(resource, request.getResource());
        assertTrue(request.getContext().isEmpty());
    }

    @Test
    void buildsRequestWithContext() {
        Context context = Context.of(Map.of("time", "1985-10-26T01:22-07:00"));
        EvaluationRequest request = EvaluationRequest.builder()
                .subject(subject).action(action).resource(resource).context(context)
                .build();

        assertEquals(Optional.of(context), request.getContext());
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThrows(IllegalStateException.class, () -> EvaluationRequest.of(null, action, resource));
        assertThrows(IllegalStateException.class, () -> EvaluationRequest.of(subject, null, resource));
        assertThrows(IllegalStateException.class, () -> EvaluationRequest.of(subject, action, null));
    }

    @Test
    void doesNotExposePersonalDataInToString() {
        EvaluationRequest request = EvaluationRequest.builder()
                .subject(subject).action(action).resource(resource)
                .context(Context.of(Map.of("ip", "10.0.0.1")))
                .build();

        String text = request.toString();

        assertFalse(text.contains("alice@example.com"));
        assertFalse(text.contains("10.0.0.1"));
        assertTrue(text.contains("can_read"));
    }

    @Test
    void implementsValueEquality() {
        EvaluationRequest request = EvaluationRequest.of(subject, action, resource);
        EvaluationRequest withContext = EvaluationRequest.builder()
                .subject(subject).action(action).resource(resource).context(Context.empty())
                .build();

        assertEquals(request, EvaluationRequest.of(subject, action, resource));
        assertEquals(request.hashCode(), EvaluationRequest.of(subject, action, resource).hashCode());
        assertNotEquals(request, withContext);
        assertNotEquals(request, EvaluationRequest.of(subject, Action.of("delete"), resource));
    }
}
