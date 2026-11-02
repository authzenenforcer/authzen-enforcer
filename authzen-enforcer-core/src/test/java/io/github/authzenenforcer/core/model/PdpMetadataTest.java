package io.github.authzenenforcer.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class PdpMetadataTest {

    private static final URI EVALUATION = URI.create("https://pdp.example.com/access/v1/evaluation");

    private static PdpMetadata minimal() {
        return PdpMetadata.builder()
                .policyDecisionPoint("https://pdp.example.com")
                .accessEvaluationEndpoint(EVALUATION)
                .build();
    }

    @Test
    void buildsMetadataWithTheRequiredParametersOnly() {
        PdpMetadata metadata = minimal();

        assertEquals("https://pdp.example.com", metadata.getPolicyDecisionPoint());
        assertEquals(EVALUATION, metadata.getAccessEvaluationEndpoint());
        assertTrue(metadata.getAccessEvaluationsEndpoint().isEmpty());
        assertTrue(metadata.getSearchSubjectEndpoint().isEmpty());
        assertTrue(metadata.getSearchResourceEndpoint().isEmpty());
        assertTrue(metadata.getSearchActionEndpoint().isEmpty());
        assertTrue(metadata.getCapabilities().isEmpty());
    }

    @Test
    void buildsMetadataWithAllParameters() {
        PdpMetadata metadata = PdpMetadata.builder()
                .policyDecisionPoint("https://pdp.example.com")
                .accessEvaluationEndpoint(EVALUATION)
                .accessEvaluationsEndpoint(URI.create("https://pdp.example.com/access/v1/evaluations"))
                .searchSubjectEndpoint(URI.create("https://pdp.example.com/access/v1/search/subject"))
                .searchResourceEndpoint(URI.create("https://pdp.example.com/access/v1/search/resource"))
                .searchActionEndpoint(URI.create("https://pdp.example.com/access/v1/search/action"))
                .capability("urn:ietf:params:authzen:capability:example")
                .build();

        assertEquals(Optional.of(URI.create("https://pdp.example.com/access/v1/evaluations")),
                metadata.getAccessEvaluationsEndpoint());
        assertEquals(Optional.of(URI.create("https://pdp.example.com/access/v1/search/action")),
                metadata.getSearchActionEndpoint());
        assertEquals(List.of("urn:ietf:params:authzen:capability:example"), metadata.getCapabilities());
    }

    @Test
    void rejectsMissingRequiredParameters() {
        assertThrows(IllegalStateException.class,
                () -> PdpMetadata.builder().accessEvaluationEndpoint(EVALUATION).build());
        assertThrows(IllegalStateException.class,
                () -> PdpMetadata.builder().policyDecisionPoint("https://pdp.example.com").build());
    }

    @Test
    void keepsCapabilitiesUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () -> minimal().getCapabilities().add("urn:x"));
    }

    @Test
    void implementsValueEquality() {
        assertEquals(minimal(), minimal());
        assertEquals(minimal().hashCode(), minimal().hashCode());
        assertNotEquals(minimal(), PdpMetadata.builder()
                .policyDecisionPoint("https://pdp.example.com")
                .accessEvaluationEndpoint(EVALUATION)
                .capability("urn:x")
                .build());
    }
}
