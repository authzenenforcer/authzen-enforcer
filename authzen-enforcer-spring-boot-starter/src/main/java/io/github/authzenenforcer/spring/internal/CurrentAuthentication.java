package io.github.authzenenforcer.spring.internal;

/**
 * Supplies the Spring Security {@code Authentication} of the current request, the
 * {@code #authentication} of expressions.
 */
public final class CurrentAuthentication {

    /**
     * Creates the supplier.
     */
    public CurrentAuthentication() {
        // Holds no state
    }

    /**
     * Returns the authentication of the current request.
     *
     * @return the authentication, or {@code null} if there is none
     */
    public Object get() {
        return SecurityClaimsExtractor.authentication();
    }
}
