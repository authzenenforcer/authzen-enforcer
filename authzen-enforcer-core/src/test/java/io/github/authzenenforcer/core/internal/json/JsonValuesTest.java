package io.github.authzenenforcer.core.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JsonValuesTest {

    @Nested
    class Normalization {

        @Test
        void keepsStringsBooleansAndNull() {
            assertEquals("text", JsonValues.copyValue("text"));
            assertEquals(Boolean.TRUE, JsonValues.copyValue(true));
            assertNull(JsonValues.copyValue(null));
        }

        @Test
        void convertsCharSequencesToStrings() {
            assertEquals("text", JsonValues.copyValue(new StringBuilder("text")));
        }

        @Test
        void convertsIntegralNumbersToLong() {
            assertEquals(1L, JsonValues.copyValue((byte) 1));
            assertEquals(1L, JsonValues.copyValue((short) 1));
            assertEquals(1L, JsonValues.copyValue(1));
            assertEquals(1L, JsonValues.copyValue(BigInteger.ONE));
        }

        @Test
        void keepsIntegersOutsideLongRangeAsBigInteger() {
            BigInteger big = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
            assertEquals(big, JsonValues.copyValue(big));
        }

        @Test
        void convertsFractionsToBigDecimal() {
            assertEquals(new BigDecimal("1.5"), JsonValues.copyValue(1.5));
            assertEquals(new BigDecimal("1.5"), JsonValues.copyValue(1.5f));
            assertEquals(new BigDecimal("1.50"), JsonValues.copyValue(new BigDecimal("1.50")));
        }

        @Test
        void matchesWhatTheParserReturnsForTheWrittenValue() {
            JsonWriter writer = new JsonWriter();
            JsonParser parser = new JsonParser();
            for (Object value : List.of(0, -7, 1.0, 1e10, 2.5e-3, Long.MIN_VALUE, new BigDecimal("5"),
                    new BigDecimal("5E+3"), Double.MIN_VALUE)) {
                assertEquals(parser.parse(writer.write(value)), JsonValues.copyValue(value), value::toString);
            }
        }

        @Test
        void rejectsNonFiniteNumbers() {
            assertThrows(IllegalArgumentException.class, () -> JsonValues.copyValue(Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> JsonValues.copyValue(Float.POSITIVE_INFINITY));
        }

        @Test
        void rejectsUnsupportedTypes() {
            assertThrows(IllegalArgumentException.class, () -> JsonValues.copyValue(new Object()));
            assertThrows(IllegalArgumentException.class, () -> JsonValues.copyValue('c'));
        }
    }

    @Nested
    class Structures {

        @Test
        void deepCopiesMapsAndLists() {
            List<Object> roles = new ArrayList<>(List.of("admin"));
            Map<String, Object> nested = new HashMap<>(Map.of("roles", roles));
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("nested", nested);

            Map<String, Object> copy = JsonValues.copyObject(original);
            roles.add("user");
            nested.put("other", 1);
            original.put("late", true);

            assertEquals(Map.of("nested", Map.of("roles", List.of("admin"))), copy);
        }

        @Test
        void returnsUnmodifiableStructures() {
            Map<String, Object> copy = JsonValues.copyObject(Map.of("list", List.of(1), "map", Map.of()));
            assertThrows(UnsupportedOperationException.class, () -> copy.put("x", 1));
            assertThrows(UnsupportedOperationException.class, () -> ((List<?>) copy.get("list")).clear());
            assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) copy.get("map")).clear());
        }

        @Test
        void convertsIterablesAndArraysToLists() {
            assertEquals(List.of("a"), JsonValues.copyValue(Set.of("a")));
            assertEquals(List.of(1L, 2L), JsonValues.copyValue(new Integer[] {1, 2}));
        }

        @Test
        void keepsNullValuesAndInsertionOrder() {
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("b", null);
            original.put("a", Arrays.asList(1, null));

            Map<String, Object> copy = JsonValues.copyObject(original);

            assertEquals(List.of("b", "a"), new ArrayList<>(copy.keySet()));
            assertNull(copy.get("b"));
            assertEquals(Arrays.asList(1L, null), copy.get("a"));
        }

        @Test
        void rejectsNonStringKeys() {
            Map<Object, Object> map = new HashMap<>();
            map.put(1, "one");
            assertThrows(IllegalArgumentException.class, () -> JsonValues.copyObject(map));

            Map<Object, Object> nullKey = new HashMap<>();
            nullKey.put(null, "none");
            assertThrows(IllegalArgumentException.class, () -> JsonValues.copyObject(nullKey));
        }

        @Test
        void limitsNestingDepth() {
            Object value = "leaf";
            for (int i = 0; i < JsonValues.MAX_DEPTH; i++) {
                value = List.of(value);
            }
            JsonValues.copyValue(value);
            Object tooDeep = List.of(value);
            assertThrows(IllegalArgumentException.class, () -> JsonValues.copyValue(tooDeep));
        }

        @Test
        void stopsCycles() {
            List<Object> cyclic = new ArrayList<>();
            cyclic.add(cyclic);
            assertThrows(IllegalArgumentException.class, () -> JsonValues.copyValue(cyclic));
        }
    }
}
