package io.github.authzenenforcer.core.internal.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Strict RFC 8259 JSON parser.
 *
 * <p>Beyond the RFC grammar, the parser:
 * <ul>
 *   <li>rejects duplicate object keys, so that two parsers can never disagree on a value
 *       such as {@code "decision"};</li>
 *   <li>limits nesting depth, input length and number length to protect against resource
 *       exhaustion;</li>
 *   <li>ignores a single leading byte order mark.</li>
 * </ul>
 *
 * <p>Returned maps and lists are unmodifiable. See the package documentation for the type mapping.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class JsonParser {

    /** Default maximum nesting depth of objects and arrays. */
    public static final int DEFAULT_MAX_DEPTH = 64;

    /** Default maximum input length in characters (8 MiB). */
    public static final int DEFAULT_MAX_LENGTH = 8 * 1024 * 1024;

    private static final char BYTE_ORDER_MARK = '﻿';
    private static final int END_OF_INPUT = -1;

    // Converting very long digit strings to BigInteger/BigDecimal is super-linear, so cap their length
    private static final int MAX_NUMBER_LENGTH = 1000;

    // Any integer of up to 18 characters, including a minus sign, fits in a long
    private static final int MAX_SAFE_LONG_DIGITS = 18;

    private final int maxDepth;
    private final int maxLength;

    /**
     * Creates a parser with the default limits.
     */
    public JsonParser() {
        this(DEFAULT_MAX_DEPTH, DEFAULT_MAX_LENGTH);
    }

    /**
     * Creates a parser with custom limits.
     *
     * @param maxDepth  maximum nesting depth of objects and arrays, at least 1
     * @param maxLength maximum input length in characters, at least 1
     */
    public JsonParser(int maxDepth, int maxLength) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1");
        }
        if (maxLength < 1) {
            throw new IllegalArgumentException("maxLength must be at least 1");
        }
        this.maxDepth = maxDepth;
        this.maxLength = maxLength;
    }

    /**
     * Parses a complete JSON document.
     *
     * @param json the document
     * @return the parsed value, which may be {@code null} for the JSON literal {@code null}
     * @throws JsonException if the input is not valid JSON or exceeds a limit
     */
    public Object parse(String json) {
        Objects.requireNonNull(json, "json");
        if (json.length() > maxLength) {
            throw new JsonException("Input exceeds the maximum length of " + maxLength + " characters", 0);
        }
        return new Reader(json).readDocument();
    }

    /** Parsing state for a single document. */
    private final class Reader {

        private final String in;
        private int pos;
        private int depth;

        Reader(String in) {
            this.in = in;
        }

        Object readDocument() {
            if (!in.isEmpty() && in.charAt(0) == BYTE_ORDER_MARK) {
                pos = 1;
            }
            skipWhitespace();
            Object value = readValue();
            skipWhitespace();
            if (pos < in.length()) {
                throw error("Unexpected content after the JSON value");
            }
            return value;
        }

        private Object readValue() {
            int c = peek();
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    readLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    readLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    readLiteral("null");
                    return null;
                case END_OF_INPUT:
                    throw error("Unexpected end of input");
                default:
                    if (c == '-' || isDigit(c)) {
                        return readNumber();
                    }
                    throw error("Unexpected character " + describe(c));
            }
        }

        private Map<String, Object> readObject() {
            enterNesting();
            pos++;
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                depth--;
                return Collections.unmodifiableMap(object);
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected a string key");
                }
                int keyPosition = pos;
                String key = readString();
                if (object.containsKey(key)) {
                    throw new JsonException("Duplicate object key", keyPosition);
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();
                object.put(key, readValue());
                skipWhitespace();
                int c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    depth--;
                    return Collections.unmodifiableMap(object);
                } else {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        private List<Object> readArray() {
            enterNesting();
            pos++;
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                depth--;
                return Collections.unmodifiableList(array);
            }
            while (true) {
                skipWhitespace();
                array.add(readValue());
                skipWhitespace();
                int c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    depth--;
                    return Collections.unmodifiableList(array);
                } else {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        private String readString() {
            int start = pos;
            pos++;
            StringBuilder value = new StringBuilder();
            int chunkStart = pos;
            while (true) {
                if (pos >= in.length()) {
                    throw new JsonException("Unterminated string", start);
                }
                char c = in.charAt(pos);
                if (c == '"') {
                    value.append(in, chunkStart, pos);
                    pos++;
                    return value.toString();
                }
                if (c == '\\') {
                    value.append(in, chunkStart, pos);
                    pos++;
                    readEscape(value);
                    chunkStart = pos;
                } else if (c < 0x20) {
                    throw error("Unescaped control character in string");
                } else {
                    pos++;
                }
            }
        }

        private void readEscape(StringBuilder value) {
            int c = peek();
            switch (c) {
                case '"':
                case '\\':
                case '/':
                    value.append((char) c);
                    break;
                case 'b':
                    value.append('\b');
                    break;
                case 'f':
                    value.append('\f');
                    break;
                case 'n':
                    value.append('\n');
                    break;
                case 'r':
                    value.append('\r');
                    break;
                case 't':
                    value.append('\t');
                    break;
                case 'u':
                    value.append(readUnicodeEscape());
                    return;
                case END_OF_INPUT:
                    throw error("Unterminated escape sequence");
                default:
                    throw error("Invalid escape sequence");
            }
            pos++;
        }

        private char readUnicodeEscape() {
            pos++;
            if (pos + 4 > in.length()) {
                throw error("Incomplete unicode escape");
            }
            int code = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(in.charAt(pos), 16);
                if (digit < 0) {
                    throw error("Invalid hex digit in unicode escape");
                }
                code = code * 16 + digit;
                pos++;
            }
            return (char) code;
        }

        private Object readNumber() {
            int start = pos;
            boolean integral = true;
            if (peek() == '-') {
                pos++;
            }
            if (peek() == '0') {
                pos++;
                if (isDigit(peek())) {
                    throw error("Leading zeros are not allowed");
                }
            } else if (isDigit(peek())) {
                skipDigits();
            } else {
                throw error("Expected a digit");
            }
            if (peek() == '.') {
                integral = false;
                pos++;
                if (!isDigit(peek())) {
                    throw error("Expected a digit after the decimal point");
                }
                skipDigits();
            }
            if (peek() == 'e' || peek() == 'E') {
                integral = false;
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                if (!isDigit(peek())) {
                    throw error("Expected a digit in the exponent");
                }
                skipDigits();
            }
            if (pos - start > MAX_NUMBER_LENGTH) {
                throw new JsonException("Number exceeds " + MAX_NUMBER_LENGTH + " characters", start);
            }
            String text = in.substring(start, pos);
            if (integral) {
                if (text.length() <= MAX_SAFE_LONG_DIGITS) {
                    return Long.valueOf(text);
                }
                BigInteger number = new BigInteger(text);
                return number.bitLength() < Long.SIZE ? Long.valueOf(number.longValue()) : number;
            }
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new JsonException("Number out of range", start);
            }
        }

        private void readLiteral(String literal) {
            if (!in.startsWith(literal, pos)) {
                throw error("Invalid literal");
            }
            pos += literal.length();
        }

        private void expect(char expected) {
            if (peek() != expected) {
                throw error("Expected '" + expected + "'");
            }
            pos++;
        }

        private void enterNesting() {
            depth++;
            if (depth > maxDepth) {
                throw error("Maximum nesting depth of " + maxDepth + " exceeded");
            }
        }

        private void skipWhitespace() {
            while (pos < in.length()) {
                char c = in.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        private void skipDigits() {
            while (isDigit(peek())) {
                pos++;
            }
        }

        private int peek() {
            return pos < in.length() ? in.charAt(pos) : END_OF_INPUT;
        }

        private JsonException error(String message) {
            return new JsonException(message, pos);
        }
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static String describe(int c) {
        if (c >= 0x20 && c < 0x7F) {
            return "'" + (char) c + "'";
        }
        return String.format("U+%04X", c);
    }
}
