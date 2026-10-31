package io.github.authzenenforcer.core.internal.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

/**
 * Serializes plain JDK values to compact RFC 8259 JSON.
 *
 * <p>Supported values: {@code null}, {@link CharSequence}, {@link Boolean}, {@link Byte},
 * {@link Short}, {@link Integer}, {@link Long}, {@link BigInteger}, {@link BigDecimal},
 * finite {@link Float} and {@link Double}, {@link Map} with {@link String} keys,
 * {@link Iterable} and {@code Object[]}. Any other type is rejected rather than serialized
 * through {@code toString()}.
 *
 * <p>Unpaired UTF-16 surrogates are written as {@code \}{@code uXXXX} escapes, so the output is
 * always valid UTF-8 when encoded.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class JsonWriter {

    /** Default maximum nesting depth of objects and arrays. */
    public static final int DEFAULT_MAX_DEPTH = 64;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private final int maxDepth;

    /**
     * Creates a writer with the default nesting limit.
     */
    public JsonWriter() {
        this(DEFAULT_MAX_DEPTH);
    }

    /**
     * Creates a writer with a custom nesting limit. The limit also stops cyclic structures.
     *
     * @param maxDepth maximum nesting depth of objects and arrays, at least 1
     */
    public JsonWriter(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1");
        }
        this.maxDepth = maxDepth;
    }

    /**
     * Serializes a value.
     *
     * @param value the value, may be {@code null}
     * @return the JSON text
     * @throws IllegalArgumentException if the value contains an unsupported type, a non-string map
     *                                  key, a non-finite number, or exceeds the nesting limit
     */
    public String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, 0);
        return out.toString();
    }

    private void writeValue(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof CharSequence) {
            writeString(out, value.toString());
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number) {
            writeNumber(out, (Number) value);
        } else if (value instanceof Map) {
            writeObject(out, (Map<?, ?>) value, depth + 1);
        } else if (value instanceof Iterable) {
            writeArray(out, ((Iterable<?>) value).iterator(), depth + 1);
        } else if (value instanceof Object[]) {
            writeArray(out, Arrays.asList((Object[]) value).iterator(), depth + 1);
        } else {
            throw new IllegalArgumentException("Unsupported type for JSON: " + value.getClass().getName());
        }
    }

    private void writeObject(StringBuilder out, Map<?, ?> object, int depth) {
        checkDepth(depth);
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("JSON object keys must be strings, found: "
                        + (entry.getKey() == null ? "null" : entry.getKey().getClass().getName()));
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, (String) entry.getKey());
            out.append(':');
            writeValue(out, entry.getValue(), depth);
        }
        out.append('}');
    }

    private void writeArray(StringBuilder out, Iterator<?> elements, int depth) {
        checkDepth(depth);
        out.append('[');
        boolean first = true;
        while (elements.hasNext()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeValue(out, elements.next(), depth);
        }
        out.append(']');
    }

    private void checkDepth(int depth) {
        if (depth > maxDepth) {
            throw new IllegalArgumentException("Maximum nesting depth of " + maxDepth
                    + " exceeded; the value may contain a cycle");
        }
    }

    private static void writeNumber(StringBuilder out, Number number) {
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("JSON cannot represent " + number);
            }
            out.append(number);
        } else if (number instanceof Integer || number instanceof Long || number instanceof Short
                || number instanceof Byte || number instanceof BigInteger || number instanceof BigDecimal) {
            out.append(number);
        } else {
            throw new IllegalArgumentException("Unsupported number type for JSON: " + number.getClass().getName());
        }
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        appendUnicodeEscape(out, c);
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                            out.append(c).append(s.charAt(i + 1));
                            i++;
                        } else {
                            appendUnicodeEscape(out, c);
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        appendUnicodeEscape(out, c);
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private static void appendUnicodeEscape(StringBuilder out, char c) {
        out.append("\\u")
                .append(HEX_DIGITS[(c >> 12) & 0xF])
                .append(HEX_DIGITS[(c >> 8) & 0xF])
                .append(HEX_DIGITS[(c >> 4) & 0xF])
                .append(HEX_DIGITS[c & 0xF]);
    }
}
