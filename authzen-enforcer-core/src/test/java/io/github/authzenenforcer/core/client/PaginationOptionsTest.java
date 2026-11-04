package io.github.authzenenforcer.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PaginationOptionsTest {

    @Test
    void hasDefaults() {
        PaginationOptions options = PaginationOptions.defaults();

        assertEquals(PaginationOptions.DEFAULT_MAX_PAGES, options.getMaxPages());
        assertEquals(PaginationOptions.DEFAULT_MAX_RESULTS, options.getMaxResults());
        assertEquals(options, PaginationOptions.builder().build());
    }

    @Test
    void takesPositiveLimits() {
        PaginationOptions options = PaginationOptions.builder().maxPages(1).maxResults(Integer.MAX_VALUE).build();

        assertEquals(1, options.getMaxPages());
        assertEquals(Integer.MAX_VALUE, options.getMaxResults());
        assertEquals("PaginationOptions{maxPages=1, maxResults=2147483647}", options.toString());
    }

    @Test
    void rejectsLimitsBelowOne() {
        PaginationOptions.Builder builder = PaginationOptions.builder();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> builder.maxPages(0));
        assertEquals("maxPages must be positive", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> builder.maxResults(-1));
    }

    @Test
    void implementsValueEquality() {
        PaginationOptions options = PaginationOptions.builder().maxPages(5).build();

        assertEquals(options, PaginationOptions.builder().maxPages(5).build());
        assertEquals(options.hashCode(), PaginationOptions.builder().maxPages(5).build().hashCode());
        assertNotEquals(options, PaginationOptions.defaults());
        assertNotEquals(options, PaginationOptions.builder().maxPages(5).maxResults(5).build());
    }
}
