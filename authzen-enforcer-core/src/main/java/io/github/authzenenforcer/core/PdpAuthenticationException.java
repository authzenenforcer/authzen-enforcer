package io.github.authzenenforcer.core;

/**
 * Thrown when the PDP rejects the credentials of the PEP with {@code 401 Unauthorized} or
 * {@code 403 Forbidden}.
 *
 * <p>This is not an access decision about the subject: it means the PEP itself may not ask the PDP.
 * Callers must treat this as a deny.
 */
public final class PdpAuthenticationException extends AuthZenException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;

    /**
     * Creates a new exception.
     *
     * @param message    description of the problem
     * @param statusCode the HTTP status code returned by the PDP
     */
    public PdpAuthenticationException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * Returns the HTTP status code returned by the PDP.
     *
     * @return {@code 401} or {@code 403}
     */
    public int getStatusCode() {
        return statusCode;
    }
}
