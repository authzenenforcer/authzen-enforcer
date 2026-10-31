package io.github.authzenenforcer.core.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JsonWriterTest {

    private final JsonWriter writer = new JsonWriter();

    @Nested
    class Scalars {

        @Test
        void writesLiterals() {
            assertEquals("null", writer.write(null));
            assertEquals("true", writer.write(true));
            assertEquals("false", writer.write(false));
        }

        @Test
        void writesNumbers() {
            assertEquals("42", writer.write(42));
            assertEquals("-7", writer.write(-7L));
            assertEquals("3", writer.write((short) 3));
            assertEquals("4", writer.write((byte) 4));
            assertEquals("123456789012345678901234567890",
                    writer.write(new BigInteger("123456789012345678901234567890")));
            assertEquals("1.50", writer.write(new BigDecimal("1.50")));
            assertEquals("1.5", writer.write(1.5d));
            assertEquals("0.25", writer.write(0.25f));
        }

        @Test
        void rejectsNonFiniteNumbers() {
            assertThrows(IllegalArgumentException.class, () -> writer.write(Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> writer.write(Double.POSITIVE_INFINITY));
            assertThrows(IllegalArgumentException.class, () -> writer.write(Float.NEGATIVE_INFINITY));
        }

        @Test
        void rejectsUnknownNumberTypes() {
            assertThrows(IllegalArgumentException.class, () -> writer.write(new AtomicInteger(1)));
        }

        @Test
        void rejectsUnsupportedTypes() {
            assertThrows(IllegalArgumentException.class, () -> writer.write(new Object()));
            assertThrows(IllegalArgumentException.class, () -> writer.write('c'));
        }
    }

    @Nested
    class Strings {

        @Test
        void writesPlainAndUnicodeText() {
            assertEquals("\"hello\"", writer.write("hello"));
            assertEquals("\"Hüseyin Akdoğan 😀\"", writer.write("Hüseyin Akdoğan 😀"));
            assertEquals("\"sb\"", writer.write(new StringBuilder("sb")));
        }

        @Test
        void escapesSpecialCharacters() {
            assertEquals("\"\\\" \\\\ / \\b \\f \\n \\r \\t\"", writer.write("\" \\ / \b \f \n \r \t"));
        }

        @Test
        void escapesOtherControlCharacters() {
            assertEquals("\"\\u0000\\u001f\"", writer.write("\u0000\u001f"));
        }

        @Test
        void escapesUnpairedSurrogates() {
            assertEquals("\"a\\ud83db\"", writer.write("a\uD83Db"));
            assertEquals("\"a\\ude00\"", writer.write("a\uDE00"));
            assertEquals("\"\\ud83d\"", writer.write("\uD83D"));
        }
    }

    @Nested
    class Containers {

        @Test
        void writesObjectsInIterationOrder() {
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("z", 1);
            object.put("a", null);
            object.put("m", List.of(true, "x"));
            assertEquals("{\"z\":1,\"a\":null,\"m\":[true,\"x\"]}", writer.write(object));
        }

        @Test
        void writesEmptyContainers() {
            assertEquals("{}", writer.write(Map.of()));
            assertEquals("[]", writer.write(List.of()));
            assertEquals("[]", writer.write(new Object[0]));
        }

        @Test
        void writesArraysAndIterables() {
            assertEquals("[1,\"a\",null]", writer.write(new Object[] {1, "a", null}));
            assertEquals("[1,2]", writer.write(new ArrayList<>(List.of(1, 2))));
        }

        @Test
        void rejectsNonStringKeys() {
            Map<Object, Object> numericKey = new HashMap<>();
            numericKey.put(1, "a");
            assertThrows(IllegalArgumentException.class, () -> writer.write(numericKey));

            Map<Object, Object> nullKey = new HashMap<>();
            nullKey.put(null, "a");
            assertThrows(IllegalArgumentException.class, () -> writer.write(nullKey));
        }

        @Test
        void rejectsNestingBeyondMaxDepth() {
            JsonWriter limited = new JsonWriter(2);
            assertEquals("[[]]", limited.write(List.of(List.of())));
            assertThrows(IllegalArgumentException.class, () -> limited.write(List.of(List.of(List.of()))));
        }

        @Test
        void stopsCyclicStructures() {
            List<Object> cyclic = new ArrayList<>();
            cyclic.add(cyclic);
            assertThrows(IllegalArgumentException.class, () -> writer.write(cyclic));
        }

        @Test
        void rejectsInvalidLimit() {
            assertThrows(IllegalArgumentException.class, () -> new JsonWriter(0));
        }
    }

    @Test
    void outputCanBeParsedBack() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subject", Map.of("type", "user", "id", "alice@example.com"));
        request.put("action", Map.of("name", "can_read"));
        request.put("resource", Map.of("type", "document", "id", "123",
                "properties", Map.of("tags", Arrays.asList("a", "b"), "size", 1024L, "score", new BigDecimal("0.75"))));
        request.put("context", Map.of("note", "line1\nline2 \"quoted\" \u0001 😀"));

        Object parsed = new JsonParser().parse(writer.write(request));

        assertEquals(request, parsed);
    }
}
