package io.github.authzenenforcer.core.internal.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.enforce.InferenceException;
import io.github.authzenenforcer.core.internal.enforce.RoutePattern.Literal;
import io.github.authzenenforcer.core.internal.enforce.RoutePattern.Variable;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoutePatternTest {

    @Test
    void splitsLiteralsAndVariables() {
        assertEquals(List.of(new Literal("api"), new Literal("documents"), new Variable("id")),
                RoutePattern.parse("/api/documents/{id}"));
    }

    @Test
    void skipsEmptySegments() {
        assertEquals(List.of(new Literal("documents"), new Variable("id")), RoutePattern.parse("//documents//{id}/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/documents/{id:\\d+}", "/documents/{id: [0-9]{3}}", "/documents/{ id }",
            "/documents/{id: .*/.*}"})
    void readsTheNameOfVariablesWithPatterns(String pattern) {
        assertEquals(List.of(new Literal("documents"), new Variable("id")), RoutePattern.parse(pattern));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/documents/{*path}", "/documents/*", "/documents/**", "/documents/doc?",
            "/documents/{id}.json", "/documents/v{version}", "/documents/{a}{b}", "/documents/{id",
            "/documents/id}", "/documents/{}", "/documents/{:\\d+}"})
    void refusesSegmentsItCannotInferFrom(String pattern) {
        InferenceException e = assertThrows(InferenceException.class, () -> RoutePattern.parse(pattern));
        assertTrue(e.isMisconfiguration());
    }
}
