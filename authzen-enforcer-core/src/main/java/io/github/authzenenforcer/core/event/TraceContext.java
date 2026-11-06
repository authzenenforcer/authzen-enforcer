package io.github.authzenenforcer.core.event;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A W3C Trace Context that the client sends to the PDP in the {@code traceparent} and
 * {@code tracestate} headers.
 *
 * <p>Only version {@code 00} of {@code traceparent} is accepted, with a trace id and a parent id
 * that are not all zeros. {@code tracestate} is limited to 512 characters of printable ASCII, so
 * that it cannot carry line breaks or other control characters into a header.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @see <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a>
 * @see io.github.authzenenforcer.core.spi.PepTracer
 */
public final class TraceContext {

    /** The largest accepted {@code tracestate}, as the W3C recommendation lets vendors truncate beyond it. */
    public static final int MAX_TRACESTATE_LENGTH = 512;

    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");
    private static final Pattern TRACESTATE = Pattern.compile("[\\x20-\\x7e]*");
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_PARENT_ID = "0".repeat(16);

    private final String traceparent;
    private final String tracestate;

    private TraceContext(String traceparent, String tracestate) {
        this.traceparent = traceparent;
        this.tracestate = tracestate;
    }

    /**
     * Creates a trace context.
     *
     * @param traceparent the {@code traceparent} value, version {@code 00}
     * @param tracestate  the {@code tracestate} value, or {@code null} or empty for none
     * @return the trace context
     * @throws IllegalArgumentException if a value does not follow the format
     */
    public static TraceContext of(String traceparent, String tracestate) {
        Objects.requireNonNull(traceparent, "traceparent must not be null");
        Matcher matcher = TRACEPARENT.matcher(traceparent);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("traceparent is not a W3C version 00 value");
        }
        if (matcher.group(1).equals(ZERO_TRACE_ID) || matcher.group(2).equals(ZERO_PARENT_ID)) {
            throw new IllegalArgumentException("traceparent must not have an all-zero trace id or parent id");
        }
        if (tracestate != null && (tracestate.length() > MAX_TRACESTATE_LENGTH
                || !TRACESTATE.matcher(tracestate).matches())) {
            throw new IllegalArgumentException("tracestate must be at most " + MAX_TRACESTATE_LENGTH
                    + " printable ASCII characters");
        }
        return new TraceContext(traceparent, tracestate == null || tracestate.isEmpty() ? null : tracestate);
    }

    /**
     * Returns the {@code traceparent} value.
     *
     * @return the value
     */
    public String getTraceparent() {
        return traceparent;
    }

    /**
     * Returns the {@code tracestate} value.
     *
     * @return the value, or empty if there is none
     */
    public Optional<String> getTracestate() {
        return Optional.ofNullable(tracestate);
    }

    /**
     * Returns the trace id, for example to correlate an audit record with the trace.
     *
     * @return 32 lower-case hex digits
     */
    public String getTraceId() {
        return traceparent.substring(3, 35);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TraceContext other)) {
            return false;
        }
        return traceparent.equals(other.traceparent) && Objects.equals(tracestate, other.tracestate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(traceparent, tracestate);
    }

    @Override
    public String toString() {
        return "TraceContext{traceparent=" + traceparent + (tracestate != null ? ", tracestate=" + tracestate : "")
                + "}";
    }
}
