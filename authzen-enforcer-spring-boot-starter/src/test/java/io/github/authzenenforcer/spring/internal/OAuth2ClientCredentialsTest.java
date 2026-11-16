package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.spring.FakeTokenEndpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Duration;
import java.time.Instant;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuth2ClientCredentialsTest {

    private final FakeTokenEndpoint tokens = new FakeTokenEndpoint();
    private final InMemoryClientRegistrationRepository registrations = registrations();
    private final InMemoryOAuth2AuthorizedClientService clients = new InMemoryOAuth2AuthorizedClientService(registrations);
    private final OAuth2ClientCredentials credentials = new OAuth2ClientCredentials("pdp",
            new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients), clients,
            Duration.ofSeconds(5));

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

    @Test
    void letsTheNextCallerStartAnotherTokenRequestWhenOneHangs() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        // A token endpoint that accepts the request and never answers, with a client without a timeout
        OAuth2AuthorizedClientManager hanging = request -> {
            requests.incrementAndGet();
            entered.countDown();
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        };
        OAuth2ClientCredentials hung = new OAuth2ClientCredentials("pdp", hanging, clients, Duration.ofMillis(200));
        Thread owner = new Thread(hung::getCredentialAsync);
        owner.setDaemon(true);
        owner.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<AccessCredential> waiting = hung.getCredentialAsync();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> waiting.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("did not complete within"));

            CompletableFuture<AccessCredential> next = CompletableFuture.supplyAsync(hung::getCredentialAsync)
                    .thenCompose(stage -> stage);
            assertNotSame(waiting, next);
            for (int i = 0; i < 50 && requests.get() < 2; i++) {
                Thread.sleep(100);
            }
            assertEquals(2, requests.get());
        } finally {
            never.countDown();
        }
    }

    @Test
    void dropsATokenThatIsNotAValidBearerToken() {
        OAuth2AuthorizedClientManager invalid = request -> {
            OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registrations.findByRegistrationId("pdp"),
                    OAuth2ClientCredentials.PRINCIPAL, new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                            "not a token", Instant.now(), Instant.now().plusSeconds(300)));
            clients.saveAuthorizedClient(client, request.getPrincipal());
            return client;
        };
        OAuth2ClientCredentials dropping = new OAuth2ClientCredentials("pdp", invalid, clients, Duration.ofSeconds(5));

        IllegalStateException failure = assertThrows(IllegalStateException.class, dropping::getCredential);

        assertTrue(failure.getMessage().contains("not a valid bearer token"));
        assertFalse(failure.getMessage().contains("not a token"));
        assertNull(clients.loadAuthorizedClient("pdp", OAuth2ClientCredentials.PRINCIPAL));
    }

    private static String token(AccessCredential credential) {
        return ((AccessCredential.Bearer) credential).getToken();
    }

    private InMemoryClientRegistrationRepository registrations() {
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("pdp")
                .clientId("pep")
                // Only the fake token endpoint sees it
                .clientSecret(UUID.randomUUID().toString())
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(tokens.tokenUri())
                .build());
    }
}
