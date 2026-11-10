package io.github.authzenenforcer.core.enforce;

import io.github.authzenenforcer.core.AuthZenException;

/**
 * Thrown by an inference step that cannot infer a value safely. The request is then denied without
 * asking the PDP.
 *
 * <p>The message must not contain values taken from the request, such as path variable values or
 * claims, since it is logged.
 */
public class InferenceException extends AuthZenException {

    private static final long serialVersionUID = 1L;

    private final boolean misconfiguration;

    /**
     * Creates an exception.
     *
     * @param message          what could not be inferred, without values from the request
     * @param misconfiguration {@code true} if the endpoint needs explicit values or a route
     *                         override, {@code false} if this request carries invalid input
     */
    public InferenceException(String message, boolean misconfiguration) {
        super(message);
        this.misconfiguration = misconfiguration;
    }

    /**
     * Returns whether the endpoint is configured so that nothing can be inferred for it, as opposed
     * to this request carrying invalid input. A misconfiguration fails every request to the endpoint
     * and is logged as a warning; invalid input is logged at debug level, since callers control it.
     *
     * @return {@code true} for a misconfiguration
     */
    public boolean isMisconfiguration() {
        return misconfiguration;
    }
}
