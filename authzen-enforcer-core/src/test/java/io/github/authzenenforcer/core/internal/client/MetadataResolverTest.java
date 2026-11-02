package io.github.authzenenforcer.core.internal.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.model.PdpMetadata;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks of received metadata. The discovery flow is tested against a fake PDP in
 * {@code AuthZenClientDiscoveryTest}.
 */
class MetadataResolverTest {

    private static final URI IDENTIFIER = URI.create("https://pdp.example.com/tenant1");
    private static final URI LOCATION = URI.create("https://pdp.example.com/.well-known/authzen-configuration/tenant1");

    private static PdpMetadata.Builder metadata() {
        return PdpMetadata.builder()
                .policyDecisionPoint(IDENTIFIER.toString())
                .accessEvaluationEndpoint(URI.create("https://pdp.example.com/tenant1/access/v1/evaluation"));
    }

    @Test
    void acceptsMetadataOfThePdp() {
        PdpMetadata metadata = metadata()
                .accessEvaluationsEndpoint(URI.create("https://other.example.com/evaluations"))
                .build();

        assertSame(metadata, MetadataResolver.verify(metadata, IDENTIFIER, LOCATION));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://pdp.example.com/tenant2",
            "https://pdp.example.com/tenant1/",
            "https://PDP.example.com/tenant1",
            "http://pdp.example.com/tenant1",
            "https://pdp.example.com"})
    void rejectsMetadataOfAnotherIdentifier(String identifier) {
        PdpMetadata metadata = metadata().policyDecisionPoint(identifier).build();

        assertThrows(PdpProtocolException.class, () -> MetadataResolver.verify(metadata, IDENTIFIER, LOCATION));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/access/v1/evaluation",
            "ftp://pdp.example.com/evaluation",
            "https://user@pdp.example.com/evaluation",
            "https://pdp.example.com/evaluation?tenant=1",
            "https://pdp.example.com/evaluation#top",
            "http://pdp.example.com/evaluation"})
    void rejectsEndpointsThatCannotBeUsed(String endpoint) {
        PdpMetadata evaluation = metadata().accessEvaluationEndpoint(URI.create(endpoint)).build();
        PdpMetadata search = metadata().searchActionEndpoint(URI.create(endpoint)).build();

        assertThrows(PdpProtocolException.class, () -> MetadataResolver.verify(evaluation, IDENTIFIER, LOCATION));
        assertThrows(PdpProtocolException.class, () -> MetadataResolver.verify(search, IDENTIFIER, LOCATION));
    }

    @Test
    void escapesTheForeignIdentifierInTheErrorMessage() {
        PdpMetadata metadata = metadata().policyDecisionPoint("https://evil.example\r\n2026-11-02 INFO forged").build();

        PdpProtocolException e = assertThrows(PdpProtocolException.class,
                () -> MetadataResolver.verify(metadata, IDENTIFIER, LOCATION));
        assertFalse(e.getMessage().contains("\r") || e.getMessage().contains("\n"), e.getMessage());
        assertTrue(e.getMessage().contains("https://evil.example\\u000d\\u000a2026-11-02 INFO forged"));
    }

    @Test
    void allowsPlainHttpEndpointsForAPlainHttpPdp() {
        URI identifier = URI.create("http://127.0.0.1:8080/realms/demo");
        PdpMetadata metadata = PdpMetadata.builder()
                .policyDecisionPoint(identifier.toString())
                .accessEvaluationEndpoint(URI.create("http://127.0.0.1:8080/realms/demo/authzen/access/v1/evaluation"))
                .build();

        assertSame(metadata, MetadataResolver.verify(metadata, identifier, LOCATION));
    }
}
