package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.spring.FakeTokenEndpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuth2ClientCredentialsTest {

    private final FakeTokenEndpoint tokens = new FakeTokenEndpoint();
    private final OAuth2ClientCredentials credentials = credentials();

    @AfterEach
    void close() {
        tokens.close();
    }

    @Test
    void obtainsATokenWithTheClientCredentialsGrantAndCachesIt() {
        AccessCredential first = credentials.getCredential();
        AccessCredential second = credentials.getCredential();

        assertEquals("t1", token(first));
        assertSame(first, second);
        assertEquals(1, tokens.requests().size());
        assertTrue(tokens.requests().get(0).body().contains("grant_type=client_credentials"));
        assertTrue(tokens.requests().get(0).authorization().startsWith("Basic "));
    }

    @Test
    void obtainsANewTokenWhenTheCachedOneExpiresWithinAMinute() {
        tokens.expiresIn(30);

        assertEquals("t1", token(credentials.getCredential()));
        assertEquals("t2", token(credentials.getCredential()));
    }

    @Test
    void obtainsASingleTokenForConcurrentCallers() throws Exception {
        CountDownLatch entered = tokens.hold();
        CompletableFuture<AccessCredential> owner = CompletableFuture.supplyAsync(credentials::getCredential);
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        List<CompletableFuture<AccessCredential>> waiting = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            waiting.add(credentials.getCredentialAsync());
        }
        // They wait without blocking the callers, for the same refresh
        waiting.forEach(stage -> assertFalse(stage.isDone()));
        assertSame(waiting.get(0), waiting.get(4));
        tokens.release();

        assertEquals("t1", token(owner.get(5, TimeUnit.SECONDS)));
        for (CompletableFuture<AccessCredential> stage : waiting) {
            assertEquals("t1", token(stage.get(5, TimeUnit.SECONDS)));
        }
        assertEquals(1, tokens.requests().size());
    }

    @Test
    void obtainsANewTokenAfterTheCurrentOneWasRejected() {
        AccessCredential rejected = credentials.getCredential();

        credentials.invalidate(rejected);

        assertEquals("t2", token(credentials.getCredential()));
        assertEquals(2, tokens.requests().size());
    }

    @Test
    void keepsTheTokenWhenAnOlderOneWasRejected() {
        AccessCredential older = credentials.getCredential();
        credentials.invalidate(older);
        AccessCredential current = credentials.getCredential();

        // A request that started with the older token reports it late
        credentials.invalidate(older);
        credentials.invalidate(AccessCredential.bearer("t2"));

        assertSame(current, credentials.getCredential());
        assertEquals(2, tokens.requests().size());
    }

    @Test
    void failsWhenTheTokenEndpointFailsAndTriesAgainWithTheNextCall() {
        tokens.status(401);

        CompletableFuture<AccessCredential> failed = credentials.getCredentialAsync();
        assertTrue(failed.isCompletedExceptionally());
        assertThrows(RuntimeException.class, credentials::getCredential);

        tokens.status(200);
        assertEquals("t1", token(credentials.getCredential()));
    }

    @Test
    void refusesTokensThatAreNotBearerTokens() {
        tokens.tokenType("DPoP");

        IllegalStateException failure = assertThrows(IllegalStateException.class, credentials::getCredential);

        assertTrue(failure.getMessage().contains("only bearer tokens are supported"));
    }

    private static String token(AccessCredential credential) {
        return ((AccessCredential.Bearer) credential).getToken();
    }

    private OAuth2ClientCredentials credentials() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("pdp")
                .clientId("pep")
                // Only the fake token endpoint sees it
                .clientSecret(UUID.randomUUID().toString())
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(tokens.tokenUri())
                .build();
        InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration);
        InMemoryOAuth2AuthorizedClientService clients = new InMemoryOAuth2AuthorizedClientService(registrations);
        return new OAuth2ClientCredentials("pdp",
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients), clients);
    }
}
