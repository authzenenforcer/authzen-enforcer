package io.github.authzenenforcer.core.internal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.client.PaginationOptions;
import io.github.authzenenforcer.core.model.PageResponse;
import io.github.authzenenforcer.core.model.SearchResponse;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The guards of {@link PageCursor}. The requests are plain page numbers; the tokens do not end up in
 * them, so that only the cursor itself could keep a token.
 */
class PageCursorTest {

    private static PageCursor<Integer, String> cursor(Optional<String> firstToken) {
        return new PageCursor<>("Test search", 1, firstToken, (page, token) -> page + 1, PaginationOptions.defaults());
    }

    private static SearchResponse<String> page(String nextToken) {
        return SearchResponse.<String>builder().result("r")
                .page(PageResponse.builder().nextToken(nextToken).build()).build();
    }

    @Test
    void followsDistinctTokensAndRejectsARepeatedOne() {
        PageCursor<Integer, String> cursor = cursor(Optional.empty());

        assertEquals(1, cursor.nextRequest());
        assertEquals(List.of("r"), cursor.accept(page("a")));
        assertEquals(2, cursor.nextRequest());
        cursor.accept(page("b"));
        cursor.nextRequest();

        assertThrows(PdpProtocolException.class, () -> cursor.accept(page("a")));
    }

    @Test
    void rejectsTheTokenOfTheFirstRequest() {
        PageCursor<Integer, String> cursor = cursor(Optional.of("start"));
        cursor.nextRequest();

        assertThrows(PdpProtocolException.class, () -> cursor.accept(page("start")));
    }

    @Test
    void tellsTokensWithUnpairedSurrogatesApart() {
        PageCursor<Integer, String> cursor = cursor(Optional.empty());
        cursor.nextRequest();
        cursor.accept(page("x\uD800"));
        cursor.nextRequest();

        cursor.accept(page("x\uDC00"));
        assertTrue(cursor.hasMorePages());
    }

    @Test
    void endsAfterAnEmptyToken() {
        PageCursor<Integer, String> cursor = cursor(Optional.empty());
        cursor.nextRequest();

        cursor.accept(page(""));

        assertFalse(cursor.hasMorePages());
    }

    @Test
    void keepsNoTokenItHasSeen() {
        PageCursor<Integer, String> cursor = cursor(Optional.empty());
        cursor.nextRequest();
        WeakReference<String> token = acceptLargeToken(cursor);

        for (int i = 0; i < 50 && token.get() != null; i++) {
            System.gc();
            Thread.onSpinWait();
        }

        assertTrue(token.get() == null, "the cursor still keeps the token");
        assertTrue(cursor.hasMorePages());
    }

    // A separate frame, so that no local variable keeps the token
    private static WeakReference<String> acceptLargeToken(PageCursor<Integer, String> cursor) {
        String token = "t".repeat(1024 * 1024);
        cursor.accept(page(token));
        return new WeakReference<>(token);
    }
}
