package io.github.authzenenforcer.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class TraceContextTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void keepsAValidContext() {
        TraceContext context = TraceContext.of(TRACEPARENT, "vendor=abc,other=1");

        assertEquals(TRACEPARENT, context.getTraceparent());
        assertEquals("vendor=abc,other=1", context.getTracestate().orElseThrow());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", context.getTraceId());
    }

    @Test
    void treatsAnEmptyTracestateAsNone() {
        assertTrue(TraceContext.of(TRACEPARENT, "").getTracestate().isEmpty());
        assertTrue(TraceContext.of(TRACEPARENT, null).getTracestate().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",   // other version
        "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",   // upper case
        "00-00000000000000000000000000000000-00f067aa0ba902b7-01",   // zero trace id
        "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",   // zero parent id
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\r\nx: y",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7",
        ""
    })
    void rejectsAnInvalidTraceparent(String traceparent) {
        assertThrows(IllegalArgumentException.class, () -> TraceContext.of(traceparent, null));
    }

    @Test
    void rejectsAnInvalidTracestate() {
        assertThrows(IllegalArgumentException.class, () -> TraceContext.of(TRACEPARENT, "a=b\r\nx: y"));
        assertThrows(IllegalArgumentException.class, () -> TraceContext.of(TRACEPARENT, "a=é"));
        String longest = "a=" + "b".repeat(TraceContext.MAX_TRACESTATE_LENGTH - 2);
        assertEquals(longest, TraceContext.of(TRACEPARENT, longest).getTracestate().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> TraceContext.of(TRACEPARENT, longest + "b"));
    }
}
