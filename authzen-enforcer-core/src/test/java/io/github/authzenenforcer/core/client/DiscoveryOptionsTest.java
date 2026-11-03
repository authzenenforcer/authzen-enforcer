package io.github.authzenenforcer.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class DiscoveryOptionsTest {

    @Test
    void hasTheDocumentedDefaults() {
        DiscoveryOptions options = DiscoveryOptions.defaults();

        assertEquals(Duration.ofHours(1), options.getDefaultTtl());
        assertEquals(Duration.ofMinutes(1), options.getMinTtl());
        assertEquals(Duration.ofHours(1), options.getMaxStale());
        assertEquals(Duration.ofHours(24), options.getMaxTtl());
        assertTrue(options.isHonorCacheHeaders());
        assertEquals(options, DiscoveryOptions.builder().build());
        assertSame(options, DiscoveryOptions.defaults());
    }

    @Test
    void buildsCustomOptions() {
        DiscoveryOptions options = DiscoveryOptions.builder()
                .defaultTtl(Duration.ofMinutes(30))
                .minTtl(Duration.ofSeconds(10))
                .maxStale(Duration.ZERO)
                .build();

        assertEquals(Duration.ofMinutes(30), options.getDefaultTtl());
        assertEquals(Duration.ofSeconds(10), options.getMinTtl());
        assertEquals(Duration.ZERO, options.getMaxStale());
    }

    @Test
    void acceptsAMinTtlEqualToTheDefaultTtl() {
        DiscoveryOptions options = DiscoveryOptions.builder()
                .defaultTtl(Duration.ofSeconds(30)).minTtl(Duration.ofSeconds(30)).build();

        assertEquals(options.getDefaultTtl(), options.getMinTtl());
    }

    @Test
    void rejectsADefaultTtlLongerThanTheMaxTtl() {
        DiscoveryOptions.Builder builder = DiscoveryOptions.builder().maxTtl(Duration.ofMinutes(30));

        assertThrows(IllegalStateException.class, builder::build);
        assertEquals(Duration.ofMinutes(30), builder.defaultTtl(Duration.ofMinutes(30)).build().getMaxTtl());
        assertThrows(IllegalArgumentException.class, () -> builder.maxTtl(Duration.ZERO));
    }

    @Test
    void acceptsADefaultTtlLongerThanTheMaxTtlWhenCacheHeadersAreIgnored() {
        DiscoveryOptions options = DiscoveryOptions.builder()
                .defaultTtl(Duration.ofDays(2)).honorCacheHeaders(false).build();

        assertEquals(Duration.ofDays(2), options.getDefaultTtl());
    }

    @Test
    void canIgnoreCacheHeaders() {
        DiscoveryOptions options = DiscoveryOptions.builder().honorCacheHeaders(false).build();

        assertFalse(options.isHonorCacheHeaders());
        assertNotEquals(DiscoveryOptions.defaults(), options);
    }

    @Test
    void rejectsAMinTtlBelowTheFloor() {
        DiscoveryOptions.Builder builder = DiscoveryOptions.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.minTtl(Duration.ofMillis(999)));
        assertThrows(IllegalArgumentException.class, () -> builder.minTtl(Duration.ofNanos(1)));
        assertEquals(DiscoveryOptions.MIN_TTL_FLOOR,
                builder.minTtl(DiscoveryOptions.MIN_TTL_FLOOR).defaultTtl(Duration.ofSeconds(1)).build().getMinTtl());
    }

    @Test
    void rejectsAMinTtlLongerThanTheDefaultTtl() {
        DiscoveryOptions.Builder builder = DiscoveryOptions.builder().defaultTtl(Duration.ofSeconds(30));

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void rejectsZeroNegativeAndOverlongDurations() {
        DiscoveryOptions.Builder builder = DiscoveryOptions.builder();
        Duration tooLong = DiscoveryOptions.MAX_DURATION.plusNanos(1);

        assertThrows(IllegalArgumentException.class, () -> builder.defaultTtl(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.minTtl(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.maxStale(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> builder.defaultTtl(tooLong));
        assertThrows(IllegalArgumentException.class, () -> builder.minTtl(tooLong));
        assertThrows(IllegalArgumentException.class, () -> builder.maxStale(tooLong));
        assertThrows(NullPointerException.class, () -> builder.maxStale(null));
    }

    @Test
    void implementsValueEquality() {
        DiscoveryOptions a = DiscoveryOptions.builder().maxStale(Duration.ofMinutes(5)).build();
        DiscoveryOptions b = DiscoveryOptions.builder().maxStale(Duration.ofMinutes(5)).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, DiscoveryOptions.defaults());
        assertEquals("DiscoveryOptions{defaultTtl=PT1H, minTtl=PT1M, maxStale=PT5M, maxTtl=PT24H, honorCacheHeaders=true}", a.toString());
    }
}
