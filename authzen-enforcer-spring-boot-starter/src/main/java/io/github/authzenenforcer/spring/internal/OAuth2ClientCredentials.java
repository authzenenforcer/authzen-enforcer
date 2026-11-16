package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;

import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Supplies the PEP's bearer token from a client registration of Spring Security's OAuth 2.0 client,
 * obtained with the client credentials grant.
 *
 * <p>The token is cached until shortly before it expires. Then a single caller obtains a new one,
 * on its own thread, while the others wait for its result without blocking. A token request that
 * takes longer than the refresh timeout fails the callers that wait for it, and the next call starts
 * another one; the thread that sent it stays blocked until the token client gives up. When the PDP
 * rejects the token, the authorized client is removed, so that the next call obtains a new token.
 *
 * <p>The manager must not depend on an HTTP request of the application: the client also asks for
 * the credential on its own threads, to repeat a request.
 */
public final class OAuth2ClientCredentials implements CredentialProvider {

    /** The principal the PEP's authorized client is stored under; one token serves every request. */
    public static final String PRINCIPAL = "authzen-enforcer";

    // As ClientCredentialsOAuth2AuthorizedClientProvider, so that the manager renews a token this
    // class no longer uses, rather than returning it again
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final String registrationId;
    private final OAuth2AuthorizedClientManager manager;
    private final OAuth2AuthorizedClientService clients;
    private final Duration refreshTimeout;
    private final Clock clock;
    private final AtomicReference<Token> current = new AtomicReference<>();
    private final ReentrantLock lock = new ReentrantLock();
    private CompletableFuture<AccessCredential> refresh;

    /**
     * Creates the provider.
     *
     * @param registrationId the client registration, with the client credentials grant
     * @param manager        obtains and stores the authorized client
     * @param clients        stores the authorized client, so that a rejected one can be removed
     * @param refreshTimeout how long callers wait for a token request before another may start
     * @throws IllegalArgumentException if the refresh timeout is not positive
     */
    public OAuth2ClientCredentials(String registrationId, OAuth2AuthorizedClientManager manager,
            OAuth2AuthorizedClientService clients, Duration refreshTimeout) {
        this(registrationId, manager, clients, refreshTimeout, Clock.systemUTC());
    }

    OAuth2ClientCredentials(String registrationId, OAuth2AuthorizedClientManager manager,
            OAuth2AuthorizedClientService clients, Duration refreshTimeout, Clock clock) {
        this.registrationId = Objects.requireNonNull(registrationId, "registrationId must not be null");
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.clients = Objects.requireNonNull(clients, "clients must not be null");
        this.refreshTimeout = Objects.requireNonNull(refreshTimeout, "refreshTimeout must not be null");
        if (refreshTimeout.isNegative() || refreshTimeout.isZero()) {
            throw new IllegalArgumentException("refreshTimeout must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public AccessCredential getCredential() {
        try {
            return getCredentialAsync().join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    @Override
    public CompletableFuture<AccessCredential> getCredentialAsync() {
        Token token = current.get();
        if (isUsable(token)) {
            return CompletableFuture.completedFuture(token.credential());
        }
        CompletableFuture<AccessCredential> pending;
        lock.lock();
        try {
            // Another caller may have obtained a token since
            token = current.get();
            if (isUsable(token)) {
                return CompletableFuture.completedFuture(token.credential());
            }
            if (refresh != null) {
                return refresh;
            }
            pending = new CompletableFuture<>();
            refresh = pending;
        } finally {
            lock.unlock();
        }
        // A token request without a timeout of its own must not hold every later caller
        CompletableFuture.delayedExecutor(refreshTimeout.toNanos(), TimeUnit.NANOSECONDS)
                .execute(() -> abandon(pending));
        // Obtained outside the lock, which only guards the pending refresh
        AccessCredential credential = null;
        RuntimeException failure = null;
        try {
            credential = obtain();
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            release(pending);
            // An error leaves the waiting callers without a result otherwise
            if (credential == null && failure == null) {
                pending.completeExceptionally(new IllegalStateException("Obtaining the PEP's token failed"));
            }
        }
        if (failure != null) {
            pending.completeExceptionally(failure);
        } else {
            pending.complete(credential);
        }
        return pending;
    }

    private void abandon(CompletableFuture<AccessCredential> pending) {
        if (pending.isDone()) {
            return;
        }
        release(pending);
        pending.completeExceptionally(new IllegalStateException("The token request of the OAuth 2.0 client"
                + " registration '" + registrationId + "' did not complete within " + refreshTimeout));
    }

    // Only this refresh: a later one may have started after this one was abandoned
    private void release(CompletableFuture<AccessCredential> pending) {
        lock.lock();
        try {
            if (refresh == pending) {
                refresh = null;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void invalidate(AccessCredential rejected) {
        Token token = current.get();
        if (token == null || token.credential() != rejected) {
            // Already replaced, or not one of this provider's credentials
            return;
        }
        // Removed first, so that the manager does not return the rejected token again
        OAuth2AuthorizedClient stored = clients.loadAuthorizedClient(registrationId, PRINCIPAL);
        if (stored != null && stored.getAccessToken().getTokenValue().equals(token.accessToken().getTokenValue())) {
            clients.removeAuthorizedClient(registrationId, PRINCIPAL);
        }
        current.compareAndSet(token, null);
    }

    private AccessCredential obtain() {
        OAuth2AuthorizedClient client = manager.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId(registrationId)
                .principal(PRINCIPAL)
                .build());
        if (client == null) {
            throw new IllegalStateException("The OAuth 2.0 client registration '" + registrationId
                    + "' did not authorize the PEP; it needs the client credentials grant");
        }
        OAuth2AccessToken accessToken = client.getAccessToken();
        if (!OAuth2AccessToken.TokenType.BEARER.equals(accessToken.getTokenType())) {
            throw new IllegalStateException("The token endpoint of the OAuth 2.0 client registration '"
                    + registrationId + "' issued a " + accessToken.getTokenType().getValue()
                    + " token; only bearer tokens are supported");
        }
        AccessCredential credential;
        try {
            credential = AccessCredential.bearer(accessToken.getTokenValue());
        } catch (IllegalArgumentException e) {
            // The manager stored it, and would return it until it expires
            clients.removeAuthorizedClient(registrationId, PRINCIPAL);
            throw new IllegalStateException("The token endpoint of the OAuth 2.0 client registration '"
                    + registrationId + "' issued a token that is not a valid bearer token", e);
        }
        Token token = new Token(accessToken, credential);
        current.set(token);
        return token.credential();
    }

    private boolean isUsable(Token token) {
        if (token == null) {
            return false;
        }
        Instant expiresAt = token.accessToken().getExpiresAt();
        return expiresAt == null || clock.instant().isBefore(expiresAt.minus(CLOCK_SKEW));
    }

    private record Token(OAuth2AccessToken accessToken, AccessCredential credential) {
    }
}
