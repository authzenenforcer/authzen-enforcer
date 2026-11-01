package io.github.authzenenforcer.core.auth;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Supplies the credential the PEP presents to the PDP.
 *
 * <p>The SDK never talks to an authorization server: obtaining and refreshing tokens is up to the
 * implementation, typically by delegating to the OAuth client of the application framework.
 *
 * <p>The client calls {@link #getCredential()} before every request, so implementations should
 * cache the credential and refresh it before it expires. If the PDP answers {@code 401}, the client
 * calls {@link #invalidate(AccessCredential)} and repeats the request once with a fresh credential.
 *
 * <p>Implementations must be thread-safe.
 */
@FunctionalInterface
public interface CredentialProvider {

    /**
     * Returns the credential for the next request.
     *
     * @return the credential, never {@code null}
     * @throws RuntimeException if no credential can be obtained; the request then fails without
     *                          being sent
     */
    AccessCredential getCredential();

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
        Objects.requireNonNull(tokenSupplier, "tokenSupplier");
        return () -> AccessCredential.bearer(tokenSupplier.get());
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
}
