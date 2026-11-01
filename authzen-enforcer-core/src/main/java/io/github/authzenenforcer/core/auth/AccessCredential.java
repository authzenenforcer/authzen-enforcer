package io.github.authzenenforcer.core.auth;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A credential the PEP presents to the PDP.
 *
 * <p>Credentials are obtained from a {@link CredentialProvider} before every request. Their
 * {@code toString()} never reveals the secret.
 */
public sealed interface AccessCredential permits AccessCredential.Bearer {

    /**
     * Creates a bearer credential, sent as {@code Authorization: Bearer <token>}.
     *
     * @param token the access token
     * @return the credential
     * @throws IllegalArgumentException if the token is not a valid RFC 6750 {@code b64token}
     */
    static Bearer bearer(String token) {
        return new Bearer(token);
    }

    /**
     * An OAuth 2.0 bearer token (RFC 6750). Instances are immutable and thread-safe.
     */
    final class Bearer implements AccessCredential {

        // RFC 6750 section 2.1: b64token = 1*( ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/" ) *"="
        private static final Pattern B64TOKEN = Pattern.compile("[A-Za-z0-9\\-._~+/]+=*");

        private final String token;

        private Bearer(String token) {
            Objects.requireNonNull(token, "token");
            // Also stops header injection; the message must not contain the token
            if (!B64TOKEN.matcher(token).matches()) {
                throw new IllegalArgumentException("Bearer token contains characters not allowed by RFC 6750");
            }
            this.token = token;
        }

        /**
         * Returns the access token.
         *
         * @return the token
         */
        public String getToken() {
            return token;
        }

        /**
         * Returns a description without the token.
         */
        @Override
        public String toString() {
            return "Bearer{token=***}";
        }
    }
}
