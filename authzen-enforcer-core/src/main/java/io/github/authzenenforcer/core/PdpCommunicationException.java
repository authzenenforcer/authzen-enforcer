package io.github.authzenenforcer.core;

/**
 * Thrown when the PDP cannot be reached or does not answer in time, when it reports a server
 * error ({@code 5xx}), or when it still rate-limits ({@code 429}) after the retries. Also thrown
 * without contacting the PDP while a circuit breaker is open after repeated failures; the cause is
 * then the last of those failures.
 *
 * <p>Callers must treat this as a deny.
 */
public final class PdpCommunicationException extends AuthZenException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception.
     *
     * @param message description of the problem
     */
    public PdpCommunicationException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a cause.
     *
     * @param message description of the problem
     * @param cause   the underlying cause
     */
    public PdpCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
