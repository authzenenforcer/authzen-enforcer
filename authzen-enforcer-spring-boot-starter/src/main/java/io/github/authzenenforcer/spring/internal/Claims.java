package io.github.authzenenforcer.spring.internal;

import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns claims as Spring Security holds them back into JSON values: it parses the timestamps and
 * issuer of a JWT into {@link Instant} and {@link URL}, which become numbers and strings again.
 */
final class Claims {

    private Claims() {
    }

    /**
     * Copies claims with JSON-compatible values. Values of other types are left out.
     *
     * @param claims the claims
     * @return the copy
     */
    static Map<String, Object> of(Map<String, ?> claims) {
        Map<String, Object> copy = new LinkedHashMap<>();
        claims.forEach((name, value) -> {
            Object json = json(value);
            if (json != null) {
                copy.put(name, json);
            }
        });
        return copy;
    }

    private static Object json(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Instant instant) {
            // NumericDate of RFC 7519: seconds since the epoch
            return instant.getEpochSecond();
        }
        if (value instanceof URL || value instanceof URI || value instanceof CharSequence) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> object = new LinkedHashMap<>();
            map.forEach((name, member) -> {
                Object json = json(member);
                if (name instanceof String key && json != null) {
                    object.put(key, json);
                }
            });
            return object;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> array = new ArrayList<>();
            for (Object element : collection) {
                Object json = json(element);
                if (json != null) {
                    array.add(json);
                }
            }
            return array;
        }
        return null;
    }
}
