package io.github.authzenenforcer.interop;

import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Obtains PEP tokens with the OAuth 2.0 client credentials grant, as an application would.
 *
 * <p>Lives in the tests on purpose: the SDK never talks to the authorization server.
 */
final class ClientCredentialsProvider implements CredentialProvider {

    // Refresh a little before the token expires
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final URI tokenEndpoint;
    private final String clientId;
    private final String clientSecret;

    private AccessCredential current;
    private Instant expiresAt = Instant.MIN;

    ClientCredentialsProvider(URI tokenEndpoint, String clientId, String clientSecret) {
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    static ClientCredentialsProvider forPep(KeycloakPdp pdp) {
        return new ClientCredentialsProvider(pdp.tokenEndpoint(), KeycloakPdp.CLIENT_ID, pdp.clientSecret());
    }

    @Override
    public synchronized AccessCredential getCredential() {
        if (current == null || Instant.now().isAfter(expiresAt.minus(EXPIRY_MARGIN))) {
            fetch();
        }
        return current;
    }

    @Override
    public synchronized void invalidate(AccessCredential rejected) {
        if (rejected == current) {
            current = null;
        }
    }

    private void fetch() {
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("Token request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while requesting a token", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Token endpoint returned HTTP " + response.statusCode());
        }
        Map<?, ?> token = (Map<?, ?>) JsonCodec.defaultCodec().read(response.body());
        current = AccessCredential.bearer((String) token.get("access_token"));
        expiresAt = Instant.now().plusSeconds(((Number) token.get("expires_in")).longValue());
    }
}
