package io.github.authzenenforcer.core.internal.json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates and deep-copies JSON-compatible values into immutable structures.
 *
 * <p>The copy uses exactly the types that {@link JsonParser} produces, so a value survives a
 * write and parse round trip unchanged and compares equal afterwards:
 * <ul>
 *   <li>any {@link CharSequence} becomes a {@link String};</li>
 *   <li>integral numbers become {@link Long}, or {@link java.math.BigInteger} if they do not fit;</li>
 *   <li>other numbers become {@link java.math.BigDecimal};</li>
 *   <li>maps become unmodifiable, insertion-ordered maps and iterables or arrays become
 *       unmodifiable lists.</li>
 * </ul>
 */
public final class JsonValues {

    /** Maximum nesting depth of a copied value, kept well below the writer limit. */
    public static final int MAX_DEPTH = 32;

    private static final JsonWriter WRITER = new JsonWriter();
    private static final JsonParser PARSER = new JsonParser();

    private JsonValues() {
    }

    /**
     * Copies a JSON object.
     *
     * @param object the object, whose keys must be strings
     * @return an unmodifiable, insertion-ordered copy
     * @throws IllegalArgumentException if the object contains a non-string key, an unsupported
     *                                  value or is nested deeper than {@link #MAX_DEPTH}
     */
    public static Map<String, Object> copyObject(Map<?, ?> object) {
        Objects.requireNonNull(object, "object");
        return copyObject(object, 1);
    }

    /**
     * Copies any JSON value.
     *
     * @param value the value, may be {@code null}
     * @return an immutable copy
     * @throws IllegalArgumentException if the value is not JSON-compatible or is nested deeper than
     *                                  {@link #MAX_DEPTH}
     */
    public static Object copyValue(Object value) {
        return copyValue(value, 0);
    }

    private static Object copyValue(Object value, int depth) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence) {
            return value.toString();
        }
        if (value instanceof Number number) {
            return normalizeNumber(number);
        }
        if (value instanceof Map<?, ?> map) {
            return copyObject(map, depth + 1);
        }
        if (value instanceof Iterable<?> iterable) {
            return copyArray(iterable, depth + 1);
        }
        if (value instanceof Object[] array) {
            return copyArray(Arrays.asList(array), depth + 1);
        }
        throw new IllegalArgumentException("Unsupported type for JSON: " + value.getClass().getName());
    }

    private static Map<String, Object> copyObject(Map<?, ?> object, int depth) {
        checkDepth(depth);
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object keys must be strings, found: "
                        + (entry.getKey() == null ? "null" : entry.getKey().getClass().getName()));
            }
            copy.put(key, copyValue(entry.getValue(), depth));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<Object> copyArray(Iterable<?> array, int depth) {
        checkDepth(depth);
        List<Object> copy = new ArrayList<>();
        for (Object element : array) {
            copy.add(copyValue(element, depth));
        }
        return Collections.unmodifiableList(copy);
    }

    // Writing and re-parsing the number yields exactly the type and scale a PDP round trip produces
    private static Object normalizeNumber(Number number) {
        String text = WRITER.write(number);
        try {
            return PARSER.parse(text);
        } catch (JsonException e) {
            throw new IllegalArgumentException("Number cannot be represented in JSON", e);
        }
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Maximum nesting depth of " + MAX_DEPTH
                    + " exceeded; the value may contain a cycle");
        }
    }
}
