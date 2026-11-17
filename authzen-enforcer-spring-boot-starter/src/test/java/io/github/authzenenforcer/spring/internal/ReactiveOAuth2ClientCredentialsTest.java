package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.spring.FakeTokenEndpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactiveOAuth2ClientCredentialsTest {

    private final FakeTokenEndpoint tokens = new FakeTokenEndpoint();
    private final InMemoryReactiveClientRegistrationRepository registrations = registrations();
    private final InMemoryReactiveOAuth2AuthorizedClientService clients =
            new InMemoryReactiveOAuth2AuthorizedClientService(registrations);
    private final ReactiveOAuth2ClientCredentials credentials = new ReactiveOAuth2ClientCredentials("pdp",
            new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(registrations, clients), clients,
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
    }

    @Test
    void obtainsANewTokenWhenTheCachedOneExpiresWithinAMinute() {
        tokens.expiresIn(30);

        assertEquals("t1", token(credentials.getCredential()));
        assertEquals("t2", token(credentials.getCredential()));
    }

    @Test
    void obtainsASingleTokenWithoutBlockingTheCaller() throws Exception {
        CountDownLatch entered = tokens.hold();
        CompletableFuture<AccessCredential> owner = credentials.getCredentialAsync();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        // The caller got its stage back while the token request is still open
        assertFalse(owner.isDone());

        List<CompletableFuture<AccessCredential>> waiting = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            waiting.add(credentials.getCredentialAsync());
        }
        waiting.forEach(stage -> assertSame(owner, stage));
        tokens.release();

        assertEquals("t1", token(owner.get(5, TimeUnit.SECONDS)));
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

        credentials.invalidate(older);
        credentials.invalidate(AccessCredential.bearer("t2"));

        assertSame(current, credentials.getCredential());
        assertEquals(2, tokens.requests().size());
    }

    @Test
    void failsWhenTheTokenEndpointFailsAndTriesAgainWithTheNextCall() {
        tokens.status(401);

        assertThrows(RuntimeException.class, credentials::getCredential);

        tokens.status(200);
        assertEquals("t1", token(credentials.getCredential()));
    }

    @Test
    void refusesTokensThatAreNotBearerTokens() {
        // Spring Security's reactive token client does not parse a DPoP response, another one may
        ReactiveOAuth2AuthorizedClientManager dpop = request -> registrations.findByRegistrationId("pdp")
                .map(registration -> new OAuth2AuthorizedClient(registration, OAuth2ClientCredentials.PRINCIPAL,
                        new OAuth2AccessToken(OAuth2AccessToken.TokenType.DPOP, "t1", Instant.now(),
                                Instant.now().plusSeconds(300))));
        ReactiveOAuth2ClientCredentials refusing = new ReactiveOAuth2ClientCredentials("pdp", dpop, clients,
                Duration.ofSeconds(5));

        IllegalStateException failure = assertThrows(IllegalStateException.class, refusing::getCredential);

        assertTrue(failure.getMessage().contains("only bearer tokens are supported"));
    }

    @Test
    void failsWhenTheDefaultTokenClientCannotReadTheResponse() {
        tokens.tokenType("DPoP");

        assertThrows(RuntimeException.class, credentials::getCredential);
    }

    @Test
    void cancelsATokenRequestThatHangsAndLetsTheNextCallerStartAnother() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        // A token client without a response timeout, against an endpoint that never answers
        ReactiveOAuth2AuthorizedClientManager hanging = request -> Mono.<OAuth2AuthorizedClient>never()
                .doOnSubscribe(subscription -> requests.incrementAndGet())
                .doOnCancel(cancelled::incrementAndGet);
        ReactiveOAuth2ClientCredentials hung = new ReactiveOAuth2ClientCredentials("pdp", hanging, clients,
                Duration.ofMillis(200));

        CompletableFuture<AccessCredential> first = hung.getCredentialAsync();
        ExecutionException failure = assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("did not complete within"));
        assertEquals(1, cancelled.get());

        hung.getCredentialAsync();
        assertEquals(2, requests.get());
    }

    @Test
    void dropsATokenThatIsNotAValidBearerToken() {
        ReactiveOAuth2AuthorizedClientManager invalid = request -> registrations.findByRegistrationId("pdp")
                .map(registration -> new OAuth2AuthorizedClient(registration, OAuth2ClientCredentials.PRINCIPAL,
                        new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "not a token", Instant.now(),
                                Instant.now().plusSeconds(300))))
                .flatMap(client -> clients.saveAuthorizedClient(client, principal()).thenReturn(client));
        ReactiveOAuth2ClientCredentials dropping = new ReactiveOAuth2ClientCredentials("pdp", invalid, clients,
                Duration.ofSeconds(5));

        IllegalStateException failure = assertThrows(IllegalStateException.class, dropping::getCredential);

        assertTrue(failure.getMessage().contains("not a valid bearer token"));
        assertFalse(failure.getMessage().contains("not a token"));
        assertNull(clients.loadAuthorizedClient("pdp", OAuth2ClientCredentials.PRINCIPAL).block());
    }

    @Test
    void waitsForTheRemovalOfARejectedTokenBeforeTheNextRequest() throws Exception {
        CompletableFuture<Void> removal = new CompletableFuture<>();
        ReactiveOAuth2AuthorizedClientService slowRemoval = new Delegating(clients) {
            @Override
            public Mono<Void> removeAuthorizedClient(String registrationId, String principalName) {
                return Mono.fromFuture(removal).then(super.removeAuthorizedClient(registrationId, principalName));
            }
        };
        ReactiveOAuth2ClientCredentials waiting = new ReactiveOAuth2ClientCredentials("pdp",
                new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(registrations, slowRemoval),
                slowRemoval, Duration.ofSeconds(5));
        AccessCredential rejected = waiting.getCredential();

        waiting.invalidate(rejected);
        CompletableFuture<AccessCredential> next = waiting.getCredentialAsync();
        assertFalse(next.isDone());
        assertEquals(1, tokens.requests().size());

        removal.complete(null);
        assertEquals("t2", token(next.get(5, TimeUnit.SECONDS)));
    }

    @Test
    void failsOnlyTheNextRequestWhenTheRemovalFails() throws Exception {
        AtomicBoolean failRemoval = new AtomicBoolean(true);
        ReactiveOAuth2AuthorizedClientService failing = new Delegating(clients) {
            @Override
            public Mono<Void> removeAuthorizedClient(String registrationId, String principalName) {
                return failRemoval.getAndSet(false)
                        ? Mono.error(new IllegalStateException("store unavailable"))
                        : super.removeAuthorizedClient(registrationId, principalName);
            }
        };
        ReactiveOAuth2ClientCredentials removing = new ReactiveOAuth2ClientCredentials("pdp",
                new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(registrations, failing), failing,
                Duration.ofSeconds(5));
        AccessCredential rejected = removing.getCredential();

        removing.invalidate(rejected);
        assertThrows(IllegalStateException.class, removing::getCredential);

        // The stored token was not removed, so the manager returns it again until it is rejected again
        AccessCredential again = removing.getCredential();
        removing.invalidate(again);
        assertEquals("t2", token(removing.getCredential()));
    }

    private static String token(AccessCredential credential) {
        return ((AccessCredential.Bearer) credential).getToken();
    }

    private static Authentication principal() {
        return new TestingAuthenticationToken(OAuth2ClientCredentials.PRINCIPAL, null);
    }

    private InMemoryReactiveClientRegistrationRepository registrations() {
        return new InMemoryReactiveClientRegistrationRepository(ClientRegistration.withRegistrationId("pdp")
                .clientId("pep")
                // Only the fake token endpoint sees it
                .clientSecret(UUID.randomUUID().toString())
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(tokens.tokenUri())
                .build());
    }

    /** Stores in another service, so that a test can change one operation. */
    private static class Delegating implements ReactiveOAuth2AuthorizedClientService {

        private final ReactiveOAuth2AuthorizedClientService delegate;

        Delegating(ReactiveOAuth2AuthorizedClientService delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T extends OAuth2AuthorizedClient> Mono<T> loadAuthorizedClient(String registrationId,
                String principalName) {
            return delegate.loadAuthorizedClient(registrationId, principalName);
        }

        @Override
        public Mono<Void> saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal) {
            return delegate.saveAuthorizedClient(client, principal);
        }

        @Override
        public Mono<Void> removeAuthorizedClient(String registrationId, String principalName) {
            return delegate.removeAuthorizedClient(registrationId, principalName);
        }
    }
}
