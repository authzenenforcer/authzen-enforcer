package io.github.authzenenforcer.core.internal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UrlsTest {

    @ParameterizedTest
    @CsvSource({
            "https://pdp.example.com,             https://pdp.example.com/.well-known/authzen-configuration",
            "https://pdp.example.com/tenant1,     https://pdp.example.com/.well-known/authzen-configuration/tenant1",
            "https://pdp.example.com:8443/a/b,    https://pdp.example.com:8443/.well-known/authzen-configuration/a/b",
            "http://127.0.0.1:8080/realms/a%20b,  http://127.0.0.1:8080/.well-known/authzen-configuration/realms/a%20b"})
    void insertsTheWellKnownPathBetweenHostAndPath(String identifier, String expected) {
        assertEquals(URI.create(expected),
                Urls.wellKnown(URI.create(identifier), MetadataResolver.WELL_KNOWN_PATH));
    }

    @ParameterizedTest
    @CsvSource({
            "https://pdp.example.com/,          https://pdp.example.com",
            "https://pdp.example.com/tenant1//, https://pdp.example.com/tenant1",
            "https://pdp.example.com/tenant1,   https://pdp.example.com/tenant1"})
    void removesTerminatingSlashes(String url, String expected) {
        assertEquals(URI.create(expected), Urls.withoutTrailingSlashes(URI.create(url)));
    }

    @ParameterizedTest
    @CsvSource({
            "https://pdp.example.com,          https://pdp.example.com/access/v1/evaluation",
            "https://pdp.example.com/tenant1/, https://pdp.example.com/tenant1/access/v1/evaluation"})
    void appendsPathsToTheBasePath(String base, String expected) {
        assertEquals(URI.create(expected), Urls.append(URI.create(base), DefaultAuthZenClient.EVALUATION_PATH));
    }
}
