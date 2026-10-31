package io.github.authzenenforcer.core.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonParserTest {

    private final JsonParser parser = new JsonParser();

    @Nested
    class Literals {

        @Test
        void parsesTrueFalseAndNull() {
            assertEquals(Boolean.TRUE, parser.parse("true"));
            assertEquals(Boolean.FALSE, parser.parse("false"));
            assertNull(parser.parse("null"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"tru", "True", "nul", "falsey", "undefined"})
        void rejectsInvalidLiterals(String json) {
            assertThrows(JsonException.class, () -> parser.parse(json));
        }
    }

    @Nested
    class Numbers {

        @Test
        void parsesIntegersAsLong() {
            assertEquals(0L, parser.parse("0"));
            assertEquals(-0L, parser.parse("-0"));
            assertEquals(42L, parser.parse("42"));
            assertEquals(-42L, parser.parse("-42"));
            assertEquals(Long.MAX_VALUE, parser.parse("9223372036854775807"));
            assertEquals(Long.MIN_VALUE, parser.parse("-9223372036854775808"));
        }

        @Test
        void parsesIntegersOutsideLongRangeAsBigInteger() {
            assertEquals(new BigInteger("9223372036854775808"), parser.parse("9223372036854775808"));
            assertEquals(new BigInteger("-9223372036854775809"), parser.parse("-9223372036854775809"));
        }

        @Test
        void parsesFractionsAndExponentsAsBigDecimal() {
            assertEquals(new BigDecimal("1.5"), parser.parse("1.5"));
            assertEquals(new BigDecimal("-0.25"), parser.parse("-0.25"));
            assertEquals(new BigDecimal("1E+3"), parser.parse("1e3"));
            assertEquals(new BigDecimal("1E+3"), parser.parse("1E+3"));
            assertEquals(new BigDecimal("2.5E-3"), parser.parse("2.5e-3"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"01", "-", "+1", ".5", "1.", "1.e3", "1e", "1e+", "--1", "0x10", "NaN",
                "Infinity", "-Infinity", "1 2"})
        void rejectsInvalidNumbers(String json) {
            assertThrows(JsonException.class, () -> parser.parse(json));
        }

        @Test
        void rejectsExponentOutOfRange() {
            assertThrows(JsonException.class, () -> parser.parse("1e99999999999"));
        }

        @Test
        void rejectsOverlongNumbers() {
            String digits = String.join("", Collections.nCopies(1001, "1"));
            JsonException e = assertThrows(JsonException.class, () -> parser.parse("[" + digits + "]"));
            assertEquals(1, e.getPosition());
        }
    }

    @Nested
    class Strings {

        @Test
        void parsesPlainString() {
            assertEquals("hello", parser.parse("\"hello\""));
            assertEquals("", parser.parse("\"\""));
        }

        @Test
        void parsesEscapes() {
            assertEquals("\" \\ / \b \f \n \r \t", parser.parse("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\""));
        }

        @Test
        void parsesUnicodeEscapes() {
            assertEquals("é", parser.parse("\"\\u00e9\""));
            assertEquals("é", parser.parse("\"\\u00E9\""));
            assertEquals("\uD83D\uDE00", parser.parse("\"\\ud83d\\ude00\""));
        }

        @Test
        void keepsNonAsciiCharactersAsIs() {
            assertEquals("Hüseyin Akdoğan 😀", parser.parse("\"Hüseyin Akdoğan 😀\""));
        }

        @ParameterizedTest
        @ValueSource(strings = {"\"abc", "\"\\", "\"\\x\"", "\"\\u12\"", "\"\\u12g4\"", "'single'"})
        void rejectsMalformedStrings(String json) {
            assertThrows(JsonException.class, () -> parser.parse(json));
        }

        @Test
        void rejectsUnescapedControlCharacters() {
            JsonException e = assertThrows(JsonException.class, () -> parser.parse("\"a\nb\""));
            assertEquals(2, e.getPosition());
        }
    }

    @Nested
    class ObjectsAndArrays {

        @Test
        void parsesEmptyContainers() {
            assertEquals(Collections.emptyMap(), parser.parse("{}"));
            assertEquals(Collections.emptyList(), parser.parse("[]"));
            assertEquals(Collections.emptyMap(), parser.parse(" { } "));
        }

        @Test
        void parsesNestedStructure() {
            Object value = parser.parse("{\"subject\":{\"type\":\"user\",\"id\":\"alice\"},"
                    + "\"roles\":[\"admin\",\"editor\"],\"active\":true,\"manager\":null}");

            Map<?, ?> object = (Map<?, ?>) value;
            assertEquals(Map.of("type", "user", "id", "alice"), object.get("subject"));
            assertEquals(List.of("admin", "editor"), object.get("roles"));
            assertEquals(Boolean.TRUE, object.get("active"));
            assertTrue(object.containsKey("manager"));
            assertNull(object.get("manager"));
        }

        @Test
        void preservesKeyOrder() {
            Map<?, ?> object = (Map<?, ?>) parser.parse("{\"z\":1,\"a\":2,\"m\":3}");
            assertEquals(Arrays.asList("z", "a", "m"), List.copyOf(object.keySet()));
        }

        @Test
        void acceptsAllJsonWhitespace() {
            assertEquals(List.of(1L, 2L), parser.parse(" \t\r\n[ 1 ,\n\t2 ]\r\n "));
        }

        @Test
        void returnsUnmodifiableCollections() {
            Map<?, ?> object = (Map<?, ?>) parser.parse("{\"list\":[1]}");
            assertThrows(UnsupportedOperationException.class, object::clear);
            assertThrows(UnsupportedOperationException.class, () -> ((List<?>) object.get("list")).clear());
        }

        @Test
        void rejectsDuplicateKeys() {
            JsonException e = assertThrows(JsonException.class,
                    () -> parser.parse("{\"decision\":false,\"decision\":true}"));
            assertEquals(18, e.getPosition());
        }

        @ParameterizedTest
        @ValueSource(strings = {"{", "[", "[1,]", "{\"a\":1,}", "[1 2]", "{\"a\" 1}", "{a:1}", "{1:1}",
                "{\"a\":1 \"b\":2}", "[,1]", "{,}", "]", "}"})
        void rejectsMalformedContainers(String json) {
            assertThrows(JsonException.class, () -> parser.parse(json));
        }
    }

    @Nested
    class Document {

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "true false", "{} {}", "[]x", "\u0000"})
        void rejectsEmptyOrTrailingContent(String json) {
            assertThrows(JsonException.class, () -> parser.parse(json));
        }

        @Test
        void ignoresLeadingByteOrderMark() {
            assertEquals(Collections.emptyMap(), parser.parse("\uFEFF{}"));
        }

        @Test
        void rejectsNullInput() {
            assertThrows(NullPointerException.class, () -> parser.parse(null));
        }

        @Test
        void errorMessageContainsPositionButNoInput() {
            JsonException e = assertThrows(JsonException.class, () -> parser.parse("{\"token\":secret}"));
            assertEquals(9, e.getPosition());
            assertTrue(e.getMessage().endsWith("at position 9"));
            assertFalse(e.getMessage().contains("secret"));
        }
    }

    @Nested
    class Limits {

        @Test
        void acceptsNestingUpToMaxDepth() {
            JsonParser limited = new JsonParser(3, 100);
            assertEquals(List.of(List.of(List.of())), limited.parse("[[[]]]"));
        }

        @Test
        void rejectsNestingBeyondMaxDepth() {
            JsonParser limited = new JsonParser(3, 100);
            assertThrows(JsonException.class, () -> limited.parse("[[[[]]]]"));
            assertThrows(JsonException.class, () -> limited.parse("{\"a\":{\"b\":{\"c\":{}}}}"));
        }

        @Test
        void defaultDepthStopsDeeplyNestedInput() {
            String deep = String.join("", Collections.nCopies(100_000, "["));
            assertThrows(JsonException.class, () -> parser.parse(deep));
        }

        @Test
        void rejectsInputLongerThanMaxLength() {
            JsonParser limited = new JsonParser(10, 5);
            assertEquals("abc", limited.parse("\"abc\""));
            assertThrows(JsonException.class, () -> limited.parse("\"abcd\""));
        }

        @Test
        void rejectsInvalidLimits() {
            assertThrows(IllegalArgumentException.class, () -> new JsonParser(0, 10));
            assertThrows(IllegalArgumentException.class, () -> new JsonParser(10, 0));
        }
    }
}
