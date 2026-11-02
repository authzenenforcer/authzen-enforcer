package io.github.authzenenforcer.core.internal.text;

/**
 * Makes strings from the PDP safe to put into exception messages and log records.
 *
 * <p>A PDP, or an attacker on a plain-HTTP path, controls these strings. Line breaks and other
 * control characters in them could forge log lines, so they are escaped, and long values are
 * shortened.
 */
public final class LogText {

    /** Longest rendered value before it is shortened. */
    static final int MAX_LENGTH = 256;

    private LogText() {
    }

    /**
     * Escapes control characters as {@code \}{@code uXXXX} and shortens values longer than
     * {@value #MAX_LENGTH} characters.
     *
     * @param value the untrusted value, may be {@code null}
     * @return a single-line rendering, {@code "null"} for {@code null}
     */
    public static String of(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_LENGTH) + 16);
        int end = Math.min(value.length(), MAX_LENGTH);
        for (int i = 0; i < end; i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == ' ' || c == ' ') {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        if (value.length() > MAX_LENGTH) {
            out.append("...(").append(value.length()).append(" chars)");
        }
        return out.toString();
    }
}
