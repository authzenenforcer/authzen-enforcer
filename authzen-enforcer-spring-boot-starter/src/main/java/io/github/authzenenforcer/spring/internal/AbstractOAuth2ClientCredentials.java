package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Supplies the PEP's bearer token from a client registration of Spring Security's OAuth 2.0 client,
 * obtained with the client credentials grant, for the servlet and the reactive OAuth 2.0 client.
 *
 * <p>The token is cached until shortly before it expires. Then a single caller starts a token
 * request, while the others wait for its result without blocking. A token request that takes
 * longer than the refresh timeout fails the callers that wait for it, and the next call starts
 * another one. When the PDP rejects the token, the authorized client is removed, and the next token
 * request starts once the removal completed, so that it does not return the rejected token again.
 */
public abstract class AbstractOAuth2ClientCredentials implements CredentialProvider {

    /** The principal the PEP's authorized client is stored under; one token serves every request. */
    static final String PRINCIPAL = "authzen-enforcer";

    // As the client credentials providers of Spring Security, so that the manager renews a token this
    // class no longer uses, rather than returning it again
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    private final String registrationId;
    private final Duration refreshTimeout;
    private final Clock clock;
    private final AtomicReference<Token> current = new AtomicReference<>();
    private final ReentrantLock lock = new ReentrantLock();
    private CompletableFuture<AccessCredential> refresh;
    // The removal of a rejected or invalid token, which the next token request waits for
    private CompletableFuture<Void> removal = DONE;

    AbstractOAuth2ClientCredentials(String registrationId, Duration refreshTimeout, Clock clock) {
        this.registrationId = Objects.requireNonNull(registrationId, "registrationId must not be null");
        this.refreshTimeout = Objects.requireNonNull(refreshTimeout, "refreshTimeout must not be null");
        if (refreshTimeout.isNegative() || refreshTimeout.isZero()) {
            throw new IllegalArgumentException("refreshTimeout must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Starts a token request. The servlet client completes it before returning.
     *
     * @return the authorized client, or {@code null} if the registration did not authorize the PEP
     */
    abstract CompletableFuture<OAuth2AuthorizedClient> authorize();

    /**
     * Loads the stored authorized client.
     *
     * @return the stored client, or {@code null} if there is none
     */
    abstract CompletableFuture<OAuth2AuthorizedClient> loadStored();

    /**
     * Removes the stored authorized client.
     *
     * @return completes once it is removed
     */
    abstract CompletableFuture<Void> removeStored();

    final String registrationId() {
        return registrationId;
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
        CompletableFuture<Void> removed;
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
            removed = removal;
            // A failed removal fails only this request; later ones still wait until it is over
            removal = removed.handle((done, failure) -> null);
        } finally {
            lock.unlock();
        }
        AtomicReference<CompletableFuture<OAuth2AuthorizedClient>> request = new AtomicReference<>();
        // A token request without a timeout of its own must not hold every later caller
        CompletableFuture.delayedExecutor(refreshTimeout.toNanos(), TimeUnit.NANOSECONDS)
                .execute(() -> abandon(pending, request));
        // Started outside the lock, which only guards the pending refresh
        try {
            CompletableFuture<OAuth2AuthorizedClient> started = removed.thenCompose(done -> {
                CompletableFuture<OAuth2AuthorizedClient> next = authorize();
                request.set(next);
                return next;
            });
            request.compareAndSet(null, started);
            started.whenComplete((client, failure) -> settle(pending, client, failure));
        } catch (RuntimeException | Error e) {
            // An error leaves the waiting callers without a result otherwise
            release(pending);
            pending.completeExceptionally(e instanceof RuntimeException
                    ? e
                    : new IllegalStateException("Obtaining the PEP's token failed"));
            if (e instanceof Error error) {
                throw error;
            }
        }
        return pending;
    }

    private void settle(CompletableFuture<AccessCredential> pending, OAuth2AuthorizedClient client,
            Throwable failure) {
        release(pending);
        if (failure != null) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            pending.completeExceptionally(cause instanceof RuntimeException
                    ? cause
                    : new IllegalStateException("Obtaining the PEP's token failed", cause));
            return;
        }
        try {
            pending.complete(accept(client));
        } catch (RuntimeException e) {
            pending.completeExceptionally(e);
        }
    }

    private AccessCredential accept(OAuth2AuthorizedClient client) {
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
            CompletableFuture<Void> removed = removeStored();
            remove(removed, new AtomicReference<>(removed));
            throw new IllegalStateException("The token endpoint of the OAuth 2.0 client registration '"
                    + registrationId + "' issued a token that is not a valid bearer token", e);
        }
        Token token = new Token(accessToken, credential);
        current.set(token);
        return token.credential();
    }

    private void abandon(CompletableFuture<AccessCredential> pending,
            AtomicReference<CompletableFuture<OAuth2AuthorizedClient>> request) {
        if (pending.isDone()) {
            return;
        }
        release(pending);
        pending.completeExceptionally(new IllegalStateException("The token request of the OAuth 2.0 client"
                + " registration '" + registrationId + "' did not complete within " + refreshTimeout));
        // A reactive token request is cancelled; the thread of a servlet one stays blocked until the
        // token client gives up
        CompletableFuture<OAuth2AuthorizedClient> started = request.get();
        if (started != null) {
            started.cancel(true);
        }
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
        // Removed before the next token request, so that the manager does not return the rejected
        // token again; only if it is still the stored one
        String value = token.accessToken().getTokenValue();
        // The operation of the store in progress, so that the deadline cancels it rather than only the wait
        AtomicReference<CompletableFuture<?>> operation = new AtomicReference<>();
        CompletableFuture<OAuth2AuthorizedClient> loaded = loadStored();
        operation.set(loaded);
        remove(loaded.thenCompose(stored -> {
            if (stored == null || !stored.getAccessToken().getTokenValue().equals(value)) {
                return DONE;
            }
            CompletableFuture<Void> removed = removeStored();
            operation.set(removed);
            return removed;
        }), operation);
        current.compareAndSet(token, null);
    }

    // The next token request waits for this removal as well as for any earlier one, for at most the
    // refresh timeout: a store that never answers must not hold every later token request
    private void remove(CompletableFuture<Void> removed, AtomicReference<CompletableFuture<?>> operation) {
        CompletableFuture.delayedExecutor(refreshTimeout.toNanos(), TimeUnit.NANOSECONDS).execute(() -> {
            // Cancelling a reactive operation cancels its subscription
            CompletableFuture<?> pendingOperation = operation.get();
            if (pendingOperation != null) {
                pendingOperation.cancel(true);
            }
            removed.cancel(true);
        });
        CompletableFuture<Void> bounded = removed.handle((done, failure) -> {
            if (failure == null) {
                return null;
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            if (cause instanceof CancellationException) {
                throw new IllegalStateException("Removing the rejected token of the OAuth 2.0 client registration '"
                        + registrationId + "' did not complete within " + refreshTimeout);
            }
            throw cause instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Removing the rejected token failed", cause);
        });
        lock.lock();
        try {
            CompletableFuture<Void> earlier = removal;
            removal = earlier.thenCombine(bounded, (a, b) -> null);
        } finally {
            lock.unlock();
        }
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
