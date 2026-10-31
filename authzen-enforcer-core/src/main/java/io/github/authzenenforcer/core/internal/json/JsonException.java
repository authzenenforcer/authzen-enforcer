package io.github.authzenenforcer.core.internal.json;

/**
 * Thrown when a JSON document cannot be parsed.
 *
 * <p>The message never contains fragments of the input, only the position of the error,
 * so that sensitive response content does not end up in logs.
 */
public final class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int position;

    /**
     * Creates a new exception.
     *
     * @param message  description of the problem
     * @param position zero-based character offset in the input where the problem was detected
     */
    public JsonException(String message, int position) {
        super(message + " at position " + position);
        this.position = position;
    }

    /**
     * Returns the zero-based character offset in the input where the problem was detected.
     *
     * @return the error position
     */
    public int getPosition() {
        return position;
    }
}
