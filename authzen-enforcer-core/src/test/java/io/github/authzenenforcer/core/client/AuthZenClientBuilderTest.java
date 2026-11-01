package io.github.authzenenforcer.core.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthZenClientBuilderTest {

    @Test
    void buildsWithOnlyThePdpUrl() {
        assertNotNull(AuthZenClient.builder().pdpUrl("https://pdp.example.com").build());
    }

    @Test
    void requiresThePdpUrl() {
        assertThrows(IllegalStateException.class, () -> AuthZenClient.builder().build());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pdp.example.com",
            "/relative",
            "ftp://pdp.example.com",
            "https://user:secret@pdp.example.com",
            "https://pdp.example.com?tenant=a",
            "https://pdp.example.com#fragment",
            "https:///no-host"
    })
    void rejectsInvalidPdpUrls(String url) {
        assertThrows(IllegalArgumentException.class, () -> AuthZenClient.builder().pdpUrl(url).build());
    }

    @Test
    void rejectsAnInvalidEvaluationEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> AuthZenClient.builder()
                .pdpUrl("https://pdp.example.com")
                .evaluationEndpoint(URI.create("https://pdp.example.com/evaluate?x=1"))
                .build());
    }

    @Test
    void rejectsAnHttpClientThatFollowsRedirects() {
        HttpClient redirecting = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

        assertThrows(IllegalStateException.class,
                () -> AuthZenClient.builder().pdpUrl("https://pdp.example.com").httpClient(redirecting).build());
    }

    @Test
    void rejectsAConnectTimeoutTogetherWithAnHttpClient() {
        assertThrows(IllegalStateException.class, () -> AuthZenClient.builder()
                .pdpUrl("https://pdp.example.com")
                .httpClient(HttpClient.newHttpClient())
                .connectTimeout(Duration.ofSeconds(1))
                .build());
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> AuthZenClient.builder().requestTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> AuthZenClient.builder().connectTimeout(Duration.ofSeconds(-1)));
    }
}
