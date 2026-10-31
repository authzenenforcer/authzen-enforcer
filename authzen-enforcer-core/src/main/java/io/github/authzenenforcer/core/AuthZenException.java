package io.github.authzenenforcer.core;

/**
 * Base class of all exceptions thrown by the SDK.
 *
 * <p>Messages never contain tokens, DPoP proofs, keys or fragments of PDP responses.
 */
public class AuthZenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception.
     *
     * @param message description of the problem
     */
    public AuthZenException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a cause.
     *
     * @param message description of the problem
     * @param cause   the underlying cause
     */
    public AuthZenException(String message, Throwable cause) {
        super(message, cause);
    }
}
