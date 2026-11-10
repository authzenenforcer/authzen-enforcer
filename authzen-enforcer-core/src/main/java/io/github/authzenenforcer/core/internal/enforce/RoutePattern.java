package io.github.authzenenforcer.core.internal.enforce;

import io.github.authzenenforcer.core.enforce.InferenceException;
import io.github.authzenenforcer.core.internal.text.LogText;

import java.util.ArrayList;
import java.util.List;

/**
 * The segments of a route pattern, as Spring ({@code {id}}, {@code {id:\d+}}) and JAX-RS
 * ({@code {id}}, {@code {id: [0-9]{3}}}) declare them.
 *
 * <p>Only plain segments can be inferred from: a literal, or a whole segment that is one variable.
 * Wildcards, catch-all variables and segments that mix text and variables are refused.
 */
public final class RoutePattern {

    /** A segment of a route pattern. */
    public sealed interface Segment permits Literal, Variable {
    }

    /** A literal segment, such as {@code documents}. */
    public record Literal(String text) implements Segment {
    }

    /** A segment that is one path variable, such as {@code {id}}. */
    public record Variable(String name) implements Segment {
    }

    private RoutePattern() {
    }

    /**
     * Splits a route pattern into its segments, skipping empty ones.
     *
     * @param pattern the route pattern
     * @return the segments
     * @throws InferenceException if a segment cannot be inferred from
     */
    public static List<Segment> parse(String pattern) {
        List<Segment> segments = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= pattern.length(); i++) {
            char c = i < pattern.length() ? pattern.charAt(i) : '/';
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth < 0) {
                    throw refused(pattern, "it has unbalanced braces");
                }
            } else if (c == '/' && depth == 0) {
                if (i > start) {
                    segments.add(segment(pattern, pattern.substring(start, i)));
                }
                start = i + 1;
            }
        }
        if (depth != 0) {
            throw refused(pattern, "it has unbalanced braces");
        }
        return segments;
    }

    private static Segment segment(String pattern, String text) {
        if (text.indexOf('{') < 0 && text.indexOf('}') < 0) {
            if (text.indexOf('*') >= 0 || text.indexOf('?') >= 0) {
                throw refused(pattern, "segment " + text + " is a wildcard");
            }
            return new Literal(text);
        }
        if (!text.startsWith("{") || closingBrace(text) != text.length() - 1) {
            throw refused(pattern, "segment " + text + " mixes text and variables");
        }
        String inner = text.substring(1, text.length() - 1);
        if (inner.startsWith("*")) {
            throw refused(pattern, "segment " + text + " captures the rest of the path");
        }
        int colon = inner.indexOf(':');
        String name = (colon < 0 ? inner : inner.substring(0, colon)).trim();
        if (name.isEmpty()) {
            throw refused(pattern, "segment " + text + " has no variable name");
        }
        return new Variable(name);
    }

    // The index of the brace that closes the one at index 0
    private static int closingBrace(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static InferenceException refused(String pattern, String reason) {
        return new InferenceException("Cannot infer the resource of route " + LogText.of(pattern) + ": " + reason, true);
    }
}
