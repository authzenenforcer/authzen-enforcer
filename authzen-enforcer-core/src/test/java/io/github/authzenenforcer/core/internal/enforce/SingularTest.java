package io.github.authzenenforcer.core.internal.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SingularTest {

    @ParameterizedTest
    @CsvSource({
        "documents, document", "policies, policy", "addresses, address", "boxes, box", "buzzes, buzz",
        "branches, branch", "wishes, wish", "cases, case", "responses, response", "Documents, Document",
        "POLICIES, POLICY",
        // Left alone
        "status, status", "class, class", "analysis, analysis", "data, data", "s, s",
        // Irregular words come out wrong; a dictionary names them
        "movies, movy", "statuses, statuse", "analyses, analyse"
    })
    void singularizes(String plural, String singular) {
        assertEquals(singular, Singular.of(plural));
    }
}
