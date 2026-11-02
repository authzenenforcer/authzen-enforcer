package io.github.authzenenforcer.core.internal.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogTextTest {

    @Test
    void keepsOrdinaryText() {
        assertEquals("https://pdp.example.com/tenant ä", LogText.of("https://pdp.example.com/tenant ä"));
    }

    @Test
    void escapesLineBreaksAndOtherControlCharacters() {
        assertEquals("a\\u000d\\u000aINFO forged\\u0009\\u001b[31m\\u2028b",
                LogText.of("a\r\nINFO forged\t\u001b[31m b"));
    }

    @Test
    void shortensLongValues() {
        String rendered = LogText.of("x".repeat(10_000));

        assertTrue(rendered.startsWith("x".repeat(LogText.MAX_LENGTH) + "..."));
        assertTrue(rendered.endsWith("(10000 chars)"));
    }

    @Test
    void rendersNull() {
        assertEquals("null", LogText.of(null));
    }
}
