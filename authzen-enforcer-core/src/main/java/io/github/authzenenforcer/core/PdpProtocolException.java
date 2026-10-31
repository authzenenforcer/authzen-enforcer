package io.github.authzenenforcer.core;

/**
 * Thrown when the PDP returns a response that does not follow the AuthZEN specification, for
 * example a body that is not valid JSON or a {@code decision} that is not a boolean.
 *
 * <p>Callers must treat this as a deny.
 */
public final class PdpProtocolException extends AuthZenException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception.
     *
     * @param message description of the problem
     */
    public PdpProtocolException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a cause.
     *
     * @param message description of the problem
     * @param cause   the underlying cause
     */
    public PdpProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
