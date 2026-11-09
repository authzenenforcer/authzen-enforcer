package io.github.authzenenforcer.core.auth;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Supplies the credential the PEP presents to the PDP.
 *
 * <p>The SDK never talks to an authorization server: obtaining and refreshing tokens is up to the
 * implementation, typically by delegating to the OAuth client of the application framework.
 *
 * <p>The client calls {@link #getCredentialAsync()} before every request, so implementations should
 * cache the credential and refresh it before it expires. By default it returns the result of
 * {@link #getCredential()}. A provider whose token source is asynchronous, such as the reactive OAuth
 * client of a framework, overrides {@link #getCredentialAsync()} instead, or is created with
 * {@link #async(Supplier)}, so that no thread blocks while a token is fetched. If the PDP answers
 * {@code 401}, the client calls {@link #invalidate(AccessCredential)} and repeats the request once
 * with a fresh credential.
 *
 * <p>The time it takes to supply the credential counts toward the request timeout of the client: a
 * credential that does not arrive within it fails the request without sending it.
 *
 * <p>Implementations must be thread-safe.
 */
@FunctionalInterface
public interface CredentialProvider {

    /**
     * Returns the credential for the next request.
     *
     * <p>The default {@link #getCredentialAsync()} calls this on the thread that starts the request,
     * or that repeats it, which may be a thread of the HTTP client. It should therefore return
     * quickly, for example from a cache. A call that blocks also blocks that thread, and the request
     * timeout cannot interrupt it; the request only fails once the call returns too late. While a
     * circuit breaker tests whether a failing PDP has recovered, it lets a single request through; if
     * that request blocks here, every other request is rejected until it returns.
     *
     * <p>A provider that only overrides {@link #getCredentialAsync()} must still implement this
     * method, for example by waiting for the result of {@link #getCredentialAsync()}; the client
     * itself does not call it.
     *
     * @return the credential, never {@code null}
     * @throws RuntimeException if no credential can be obtained; the request then fails without
     *                          being sent
     */
    AccessCredential getCredential();

    /**
     * Returns a stage completed with the credential for the next request.
     *
     * <p>The client calls this before every request and waits for the stage without blocking a
     * thread, at most for the request timeout. A stage that does not complete in time, completes
     * exceptionally or completes with {@code null} fails the request without sending it. The client
     * never cancels the stage, so a provider may hand the same stage to concurrent requests, for
     * example while a single token refresh is in progress; it registers at most one callback on
     * each stage, however many requests wait for it. Hand out that stage itself: a new view of it per
     * request, such as {@code thenApply(...)} or {@code minimalCompletionStage()}, is a new stage
     * that gets a callback of its own. The default returns the result of
     * {@link #getCredential()} as a completed stage.
     *
     * @return a stage completed with the credential, never {@code null}
     * @throws RuntimeException if no credential can be obtained; the request then fails without
     *                          being sent
     */
    default CompletionStage<AccessCredential> getCredentialAsync() {
        return CompletableFuture.completedFuture(getCredential());
    }

    /**
     * Tells the provider that the PDP rejected a credential, so that the next call to
     * {@link #getCredential()} returns a fresh one.
     *
     * <p>Concurrent requests may report the same rejected credential. Implementations should only
     * refresh if {@code rejected} is still their current credential. The default does nothing.
     *
     * @param rejected the credential the PDP rejected
     */
    default void invalidate(AccessCredential rejected) {
    }

    /**
     * Returns a provider that asks a supplier for a bearer token before every request.
     *
     * <p>The supplier is responsible for caching and refreshing the token.
     *
     * @param tokenSupplier supplies the current access token
     * @return the provider
     */
    static CredentialProvider bearer(Supplier<String> tokenSupplier) {
        Objects.requireNonNull(tokenSupplier, "tokenSupplier must not be null");
        return () -> AccessCredential.bearer(tokenSupplier.get());
    }

    /**
     * Returns a provider whose credentials come from an asynchronous source, which is asked before
     * every request.
     *
     * <p>The source is responsible for caching and refreshing the credential. The provider ignores
     * {@link #invalidate(AccessCredential)}; implement this interface instead to refresh the
     * credential after the PDP rejected it. Its {@link #getCredential()} blocks until the source's
     * stage completes.
     *
     * <pre>{@code
     * CredentialProvider credentials = CredentialProvider.async(() -> tokenSource.currentToken()
     *         .thenApply(AccessCredential::bearer));
     * }</pre>
     *
     * @param source supplies a stage completed with the current credential
     * @return the provider
     */
    static CredentialProvider async(Supplier<? extends CompletionStage<? extends AccessCredential>> source) {
        Objects.requireNonNull(source, "source must not be null");
        return new CredentialProvider() {

            @Override
            public AccessCredential getCredential() {
                return await(getCredentialAsync());
            }

            // The source's stage itself, so that a stage it shares stays shared; the client only
            // reads it, which a stage of a credential subtype supports
            @SuppressWarnings("unchecked")
            @Override
            public CompletionStage<AccessCredential> getCredentialAsync() {
                return (CompletionStage<AccessCredential>) source.get();
            }
        };
    }

    /**
     * Returns a provider that always presents the same bearer token.
     *
     * <p><strong>For development and tests only.</strong> Access tokens expire, often within
     * minutes, and this provider cannot refresh them.
     *
     * @param token the access token
     * @return the provider
     * @throws IllegalArgumentException if the token is not a valid RFC 6750 {@code b64token}
     */
    static CredentialProvider staticBearer(String token) {
        AccessCredential credential = AccessCredential.bearer(token);
        return () -> credential;
    }

    // Waits without toCompletableFuture(), which a stage may not support
    private static AccessCredential await(CompletionStage<AccessCredential> stage) {
        if (stage == null) {
            return null;
        }
        CompletableFuture<AccessCredential> result = new CompletableFuture<>();
        stage.whenComplete((credential, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(credential);
            }
        });
        try {
            return result.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
