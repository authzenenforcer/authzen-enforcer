package io.github.authzenenforcer.core.internal.client;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the caching headers of a metadata response, following RFC 9111 for a private cache and the
 * {@code stale-if-error} extension of RFC 5861.
 *
 * <p>The freshness lifetime comes from {@code max-age}, otherwise from {@code Expires} minus
 * {@code Date}, minus the response's age: the larger of {@code Age} and the time since its
 * {@code Date}. {@code no-store} and an unqualified {@code no-cache} make it zero. {@code s-maxage}
 * is for shared caches and ignored. An invalid or conflicting value counts as zero, as RFC 9111
 * asks for an invalid {@code max-age} or {@code Expires}. {@code no-store}, an unqualified
 * {@code no-cache}, {@code must-revalidate} and {@code proxy-revalidate} forbid using the response
 * once it is stale (RFC 9111 section 4.2.4). A {@code Cache-Control} field with an unterminated
 * quoted string cannot be read reliably and is treated as {@code no-store}.
 *
 * <p>Dates are read in the three forms RFC 9110 section 5.6.7 requires recipients to accept:
 * IMF-fixdate, rfc850 and asctime. Any other date is invalid.
 *
 * <p>The headers come from the PDP, so parsing never throws.
 */
final class CacheDirectives {

    /** Largest delta-seconds value, as RFC 9111 section 1.2.2 suggests. */
    static final long MAX_SECONDS = 2_147_483_648L;

    private static final DateTimeFormatter IMF_FIXDATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
    private static final DateTimeFormatter ASCTIME =
            DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US);

    /**
     * What a response says about its own caching.
     *
     * @param lifetime       how long it is fresh, after subtracting its age; empty if it says nothing
     * @param staleForbidden whether it must not be used once stale
     * @param staleIfError   how long it may be used once stale when refreshing fails, if it says so
     */
    record Freshness(Optional<Duration> lifetime, boolean staleForbidden, Optional<Duration> staleIfError) {

        static final Freshness NONE = new Freshness(Optional.empty(), false, Optional.empty());
    }

    private CacheDirectives() {
    }

    /**
     * Reads the caching headers.
     *
     * @param headers the response headers
     * @param now     the current wall-clock time, the time the response was received
     * @return the freshness information
     */
    static Freshness parse(HttpHeaders headers, Instant now) {
        boolean noStore = false;
        boolean mustRevalidate = false;
        long maxAge = -1;
        boolean invalidMaxAge = false;
        long staleIfError = -1;
        Optional<List<String>> directives = directives(headers.allValues("Cache-Control"));
        if (directives.isEmpty()) {
            // An unterminated quoted string may hide any directive, so assume the strictest one
            return new Freshness(Optional.of(Duration.ZERO), true, Optional.empty());
        }
        for (String directive : directives.get()) {
            int equals = directive.indexOf('=');
            String name = (equals < 0 ? directive : directive.substring(0, equals)).trim().toLowerCase(Locale.ROOT);
            String value = equals < 0 ? null : unquote(directive.substring(equals + 1).trim());
            switch (name) {
                case "no-store" -> noStore = true;
                // A qualified no-cache only concerns the named fields
                case "no-cache" -> noStore |= value == null;
                case "must-revalidate", "proxy-revalidate" -> mustRevalidate = true;
                case "max-age" -> {
                    long seconds = seconds(value);
                    if (seconds < 0 || (maxAge >= 0 && maxAge != seconds)) {
                        invalidMaxAge = true;
                    }
                    maxAge = seconds;
                }
                case "stale-if-error" -> {
                    long seconds = seconds(value);
                    if (seconds >= 0) {
                        staleIfError = staleIfError < 0 ? seconds : Math.min(staleIfError, seconds);
                    }
                }
                default -> {
                    // Other directives do not apply to a private cache of one document
                }
            }
        }
        Optional<Instant> date = single(headers, "Date").flatMap(value -> httpDate(value, now));
        Optional<Duration> lifetime;
        if (noStore || invalidMaxAge) {
            lifetime = Optional.of(Duration.ZERO);
        } else if (maxAge >= 0) {
            lifetime = Optional.of(Duration.ofSeconds(maxAge));
        } else {
            lifetime = expires(headers, date.orElse(now), now);
        }
        lifetime = lifetime.map(fresh -> minusAge(fresh, headers, date, now));
        return new Freshness(lifetime, noStore || mustRevalidate,
                staleIfError < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(staleIfError)));
    }

    private static Optional<Duration> expires(HttpHeaders headers, Instant date, Instant now) {
        List<String> values = headers.allValues("Expires");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        Optional<Instant> expires = values.size() == 1 ? httpDate(values.get(0), now) : Optional.empty();
        if (expires.isEmpty()) {
            return Optional.of(Duration.ZERO);
        }
        Duration lifetime = Duration.between(date, expires.get());
        return Optional.of(lifetime.isNegative() ? Duration.ZERO : lifetime);
    }

    // RFC 9111 section 4.2.3 without the response delay: the larger of Age and the apparent age
    private static Duration minusAge(Duration lifetime, HttpHeaders headers, Optional<Instant> date, Instant now) {
        long age = headers.firstValue("Age").map(CacheDirectives::seconds).orElse(0L);
        Duration current = Duration.ofSeconds(Math.max(age, 0));
        if (date.isPresent() && date.get().isBefore(now)) {
            Duration apparent = Duration.between(date.get(), now);
            if (apparent.compareTo(current) > 0) {
                current = apparent;
            }
        }
        Duration fresh = lifetime.minus(current);
        return fresh.isNegative() ? Duration.ZERO : fresh;
    }

    private static Optional<String> single(HttpHeaders headers, String name) {
        List<String> values = headers.allValues(name);
        return values.size() == 1 ? Optional.of(values.get(0)) : Optional.empty();
    }

    /**
     * Splits on commas outside quoted strings, honouring quoted-pair escapes (RFC 9110 section 5.6.4).
     *
     * @return the directives, or empty if a quoted string is not terminated
     */
    private static Optional<List<String>> directives(List<String> fields) {
        List<String> result = new ArrayList<>();
        for (String field : fields) {
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < field.length(); i++) {
                char c = field.charAt(i);
                if (quoted && c == '\\' && i + 1 < field.length()) {
                    current.append(c).append(field.charAt(++i));
                    continue;
                }
                if (c == '"') {
                    quoted = !quoted;
                } else if (c == ',' && !quoted) {
                    add(result, current);
                    current.setLength(0);
                    continue;
                }
                current.append(c);
            }
            if (quoted) {
                return Optional.empty();
            }
            add(result, current);
        }
        return Optional.of(result);
    }

    private static void add(List<String> result, StringBuilder directive) {
        String trimmed = directive.toString().trim();
        if (!trimmed.isEmpty()) {
            result.add(trimmed);
        }
    }

    private static String unquote(String value) {
        if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            result.append(c == '\\' && i + 1 < value.length() - 1 ? value.charAt(++i) : c);
        }
        return result.toString();
    }

    /** Parses delta-seconds; returns -1 if the value is missing or not a non-negative integer. */
    private static long seconds(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }
        long result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            result = Math.min(MAX_SECONDS, result * 10 + (c - '0'));
        }
        return result;
    }

    /** Parses an HTTP-date in IMF-fixdate, rfc850 or asctime form; empty if it is none of them. */
    static Optional<Instant> httpDate(String value, Instant now) {
        String text = value.trim();
        try {
            return Optional.of(ZonedDateTime.parse(text, IMF_FIXDATE.withZone(ZoneOffset.UTC)).toInstant());
        } catch (DateTimeParseException e) {
            // Try the obsolete forms
        }
        try {
            return Optional.of(LocalDateTime.parse(text, ASCTIME).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            // Try rfc850
        }
        return rfc850(text, now);
    }

    // A two-digit year more than 50 years ahead is the most recent past year with those digits
    private static Optional<Instant> rfc850(String text, Instant now) {
        int nowYear = now.atZone(ZoneOffset.UTC).getYear();
        for (int century : new int[] {nowYear / 100 * 100, nowYear / 100 * 100 - 100}) {
            try {
                LocalDateTime parsed = LocalDateTime.parse(text, new DateTimeFormatterBuilder()
                        .appendPattern("EEEE, dd-MMM-")
                        .appendValueReduced(ChronoField.YEAR, 2, 2, century)
                        .appendPattern(" HH:mm:ss 'GMT'")
                        .toFormatter(Locale.US));
                if (parsed.getYear() <= nowYear + 50) {
                    return Optional.of(parsed.toInstant(ZoneOffset.UTC));
                }
            } catch (DateTimeParseException e) {
                // Not this century; the weekday may only match the previous one
            }
        }
        return Optional.empty();
    }
}
