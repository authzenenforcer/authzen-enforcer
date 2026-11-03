package io.github.authzenenforcer.core.internal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CacheDirectivesTest {

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

    private static CacheDirectives.Freshness parse(String... nameValuePairs) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            map.computeIfAbsent(nameValuePairs[i], name -> new java.util.ArrayList<>()).add(nameValuePairs[i + 1]);
        }
        return CacheDirectives.parse(HttpHeaders.of(map, (name, value) -> true), NOW);
    }

    private static Optional<Duration> seconds(long seconds) {
        return Optional.of(Duration.ofSeconds(seconds));
    }

    @Test
    void saysNothingWithoutCachingHeaders() {
        CacheDirectives.Freshness freshness = parse("Content-Type", "application/json");

        assertEquals(Optional.empty(), freshness.lifetime());
        assertFalse(freshness.staleForbidden());
        assertEquals(Optional.empty(), freshness.staleIfError());
    }

    @Test
    void readsMaxAgeAndSubtractsTheAge() {
        assertEquals(seconds(300), parse("Cache-Control", "max-age=300").lifetime());
        assertEquals(seconds(240), parse("Cache-Control", "public, max-age=300", "Age", "60").lifetime());
        assertEquals(seconds(0), parse("Cache-Control", "max-age=300", "Age", "900").lifetime());
        assertEquals(seconds(300), parse("Cache-Control", "max-age=300", "Age", "-5").lifetime(), "invalid Age is ignored");
    }

    @Test
    void readsDirectivesCaseInsensitivelyAcrossHeaderLinesAndQuotes() {
        assertEquals(seconds(120), parse("Cache-Control", "MAX-AGE=\"120\"").lifetime());
        assertEquals(seconds(0), parse("Cache-Control", "max-age=120", "Cache-Control", "no-store").lifetime());
        assertEquals(seconds(120), parse("Cache-Control", "private=\"a, b\", max-age=120").lifetime());
    }

    @Test
    void ignoresSharedCacheMaxAge() {
        assertEquals(seconds(60), parse("Cache-Control", "s-maxage=3600, max-age=60").lifetime());
        assertEquals(Optional.empty(), parse("Cache-Control", "s-maxage=3600").lifetime());
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-store", "no-cache", "max-age=0", "max-age=abc", "max-age=", "max-age=-1",
            "max-age=60, max-age=120"})
    void treatsNoStoreNoCacheAndInvalidValuesAsZero(String cacheControl) {
        assertEquals(seconds(0), parse("Cache-Control", cacheControl).lifetime());
    }

    @Test
    void keepsAQualifiedNoCache() {
        assertEquals(seconds(60), parse("Cache-Control", "no-cache=\"Set-Cookie\", max-age=60").lifetime());
    }

    @Test
    void acceptsTheSameMaxAgeTwice() {
        assertEquals(seconds(60), parse("Cache-Control", "max-age=60, max-age=60").lifetime());
    }

    @Test
    void capsHugeValues() {
        assertEquals(seconds(CacheDirectives.MAX_SECONDS), parse("Cache-Control", "max-age=99999999999999999999").lifetime());
    }

    @Test
    void readsExpiresAgainstTheDateHeader() {
        assertEquals(seconds(600), parse("Expires", "Mon, 02 Nov 2026 12:10:00 GMT",
                "Date", "Mon, 02 Nov 2026 12:00:00 GMT").lifetime());
        assertEquals(seconds(300), parse("Expires", "Mon, 02 Nov 2026 12:05:00 GMT").lifetime(), "without Date: now");
        assertEquals(seconds(0), parse("Expires", "Mon, 02 Nov 2026 11:00:00 GMT").lifetime(), "in the past");
        assertEquals(seconds(0), parse("Expires", "0").lifetime(), "invalid means already expired");
        assertEquals(seconds(60), parse("Cache-Control", "max-age=60", "Expires", "Mon, 02 Nov 2026 13:00:00 GMT").lifetime(),
                "max-age wins over Expires");
    }

    @Test
    void readsRevalidationAndStaleIfError() {
        assertTrue(parse("Cache-Control", "must-revalidate").staleForbidden());
        assertTrue(parse("Cache-Control", "proxy-revalidate").staleForbidden());
        assertEquals(seconds(30), parse("Cache-Control", "stale-if-error=60, stale-if-error=30").staleIfError());
        assertEquals(Optional.empty(), parse("Cache-Control", "stale-if-error=soon").staleIfError());
    }

    @Test
    void forbidsStaleUseForNoStoreAndUnqualifiedNoCache() {
        assertTrue(parse("Cache-Control", "no-store").staleForbidden());
        assertTrue(parse("Cache-Control", "no-cache").staleForbidden());
        assertFalse(parse("Cache-Control", "no-cache=\"Set-Cookie\", max-age=60").staleForbidden());
        assertFalse(parse("Cache-Control", "max-age=60").staleForbidden());
    }

    @Test
    void honoursQuotedPairEscapes() {
        // Without quoted-pair handling the quotes still balance, but no-store ends up inside a string
        CacheDirectives.Freshness freshness = parse("Cache-Control", "ext=\"\\\"\", no-store, x=\"\\\"\"");

        assertEquals(seconds(0), freshness.lifetime(), "the escaped quotes must not hide no-store");
        assertTrue(freshness.staleForbidden());
    }

    @Test
    void treatsAnUnterminatedQuotedStringAsNoStore() {
        CacheDirectives.Freshness freshness = parse("Cache-Control", "max-age=600, ext=\"oops, stale-if-error=60");

        assertEquals(seconds(0), freshness.lifetime());
        assertTrue(freshness.staleForbidden());
        assertEquals(Optional.empty(), freshness.staleIfError());
    }

    @Test
    void subtractsTheApparentAgeWhenItExceedsAge() {
        String oneMinuteAgo = "Mon, 02 Nov 2026 11:59:00 GMT";

        assertEquals(seconds(240), parse("Cache-Control", "max-age=300", "Date", oneMinuteAgo).lifetime());
        assertEquals(seconds(200), parse("Cache-Control", "max-age=300", "Date", oneMinuteAgo, "Age", "100").lifetime());
        assertEquals(seconds(300), parse("Cache-Control", "max-age=300", "Date", "Mon, 02 Nov 2026 12:01:00 GMT").lifetime(),
                "a Date in the future gives no apparent age");
    }

    @Test
    void readsTheThreeHttpDateForms() {
        assertEquals(Optional.of(Instant.parse("2026-11-02T12:10:00Z")),
                CacheDirectives.httpDate("Mon, 02 Nov 2026 12:10:00 GMT", NOW));
        assertEquals(Optional.of(Instant.parse("2026-11-02T12:10:00Z")),
                CacheDirectives.httpDate("Monday, 02-Nov-26 12:10:00 GMT", NOW));
        assertEquals(Optional.of(Instant.parse("1999-11-02T12:10:00Z")),
                CacheDirectives.httpDate("Tuesday, 02-Nov-99 12:10:00 GMT", NOW), "more than 50 years ahead means the past");
        assertEquals(Optional.of(Instant.parse("2026-11-02T08:49:37Z")),
                CacheDirectives.httpDate("Mon Nov  2 08:49:37 2026", NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mon, 02 Nov 2026 12:10:00 +0000", "02 Nov 2026 12:10:00 GMT", "Tue, 02 Nov 2026 12:10:00 GMT",
            "Mon, 2 Nov 2026 12:10 GMT", "yesterday", ""})
    void rejectsOtherDateForms(String value) {
        assertEquals(Optional.empty(), CacheDirectives.httpDate(value, NOW));
    }

    @Test
    void readsTheKeycloakMetadataHeaders() {
        CacheDirectives.Freshness freshness = parse("Cache-Control", "no-cache, must-revalidate, no-transform, no-store");

        assertEquals(seconds(0), freshness.lifetime());
        assertTrue(freshness.staleForbidden());
    }
}
