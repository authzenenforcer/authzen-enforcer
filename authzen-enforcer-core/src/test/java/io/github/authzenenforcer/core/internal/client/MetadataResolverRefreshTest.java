package io.github.authzenenforcer.core.internal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.client.DiscoveryOptions;
import io.github.authzenenforcer.core.internal.http.PdpTransport;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.spi.JsonCodec;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Refresh behaviour of {@link MetadataResolver}, driven by a fake clock.
 */
class MetadataResolverRefreshTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration MIN_TTL = Duration.ofMinutes(1);
    private static final Duration MAX_STALE = Duration.ofMinutes(5);
    private static final DiscoveryOptions OPTIONS = DiscoveryOptions.builder()
            .defaultTtl(TTL).minTtl(MIN_TTL).maxStale(MAX_STALE).build();

    // Stands for a 200 answer whose metadata belongs to another PDP
    private static final int FOREIGN = 0;

    private HttpServer server;
    private final List<Integer> statuses = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    // Starts near the overflow point, so that the cache arithmetic must be wrap-safe
    private final AtomicLong nanos = new AtomicLong(Long.MAX_VALUE - Duration.ofMinutes(3).toNanos());
    private URI identifier;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                int index = requests.getAndIncrement();
                int answer = statuses.get(Math.min(index, statuses.size() - 1));
                int status = answer == FOREIGN ? 200 : answer;
                String document = answer == FOREIGN ? metadata().replace(identifier.toString(), "https://other.example")
                        : status == 200 ? metadata() : "";
                byte[] body = document.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        identifier = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        TestLoggerFinder.failing = false;
        server.stop(0);
    }

    private String metadata() {
        return "{\"policy_decision_point\": \"" + identifier + "\", "
                + "\"access_evaluation_endpoint\": \"" + identifier + "/authzen/access/v1/evaluation\"}";
    }

    private static PdpTransport transport() {
        return new PdpTransport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                null, JsonCodec.defaultCodec(), Duration.ofSeconds(2), 0);
    }

    private MetadataResolver resolver(DiscoveryOptions options) {
        return new MetadataResolver(transport(), identifier, options, false, nanos::get);
    }

    private MetadataResolver resolver() {
        return resolver(OPTIONS);
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    void keepsAResultForTheDefaultTtl() {
        statuses.addAll(List.of(200, 200));
        MetadataResolver resolver = resolver();

        resolver.metadata().join();
        advance(TTL.minusNanos(1));
        resolver.metadata().join();
        assertEquals(1, requests.get());

        advance(Duration.ofNanos(1));
        resolver.metadata().join();
        assertEquals(2, requests.get());
    }

    @Test
    void checksA404ThatReplacesPublishedMetadataAgainAfterMinTtl() {
        statuses.addAll(List.of(200, 404, 200));
        MetadataResolver resolver = resolver();

        assertTrue(resolver.metadata().join().isPresent());
        advance(TTL);
        assertEquals(Optional.empty(), resolver.metadata().join());
        advance(MIN_TTL);

        assertTrue(resolver.metadata().join().isPresent(), "the 404 must not be kept for the default TTL");
        assertEquals(3, requests.get());
    }

    @Test
    void keepsA404WithoutPreviousMetadataForTheDefaultTtl() {
        statuses.addAll(List.of(404, 200));
        MetadataResolver resolver = resolver();

        assertEquals(Optional.empty(), resolver.metadata().join());
        advance(MIN_TTL);

        assertEquals(Optional.empty(), resolver.metadata().join());
        assertEquals(1, requests.get());
    }

    @Test
    void keepsThePreviousMetadataWhileThePdpRateLimits() {
        statuses.addAll(List.of(200, 429));
        MetadataResolver resolver = resolver();
        PdpMetadata first = resolver.metadata().join().orElseThrow();
        advance(TTL);

        assertEquals(Optional.of(first), resolver.metadata().join());
        assertEquals(2, requests.get());
    }

    @Test
    void retriesAFailedRefreshAfterMinTtlWhileUsingThePreviousMetadata() {
        statuses.addAll(List.of(200, 503, 503, 200));
        MetadataResolver resolver = resolver();
        resolver.metadata().join();
        advance(TTL);

        resolver.metadata().join();
        advance(MIN_TTL.minusNanos(1));
        resolver.metadata().join();
        assertEquals(2, requests.get(), "no new attempt before minTtl");

        advance(Duration.ofNanos(1));
        resolver.metadata().join();
        assertEquals(3, requests.get());
    }

    @Test
    void usesThePreviousMetadataUntilMaxStaleAfterItsExpiry() {
        statuses.addAll(List.of(200, 503));
        MetadataResolver resolver = resolver();
        PdpMetadata first = resolver.metadata().join().orElseThrow();

        advance(TTL.plus(MAX_STALE).minusNanos(1));

        assertEquals(Optional.of(first), resolver.metadata().join());
    }

    @Test
    void failsOnceThePreviousMetadataIsOlderThanMaxStale() {
        statuses.addAll(List.of(200, 503));
        MetadataResolver resolver = resolver();
        resolver.metadata().join();

        advance(TTL.plus(MAX_STALE));

        CompletionException e = assertThrows(CompletionException.class, () -> resolver.metadata().join());
        assertTrue(e.getCause() instanceof PdpCommunicationException, String.valueOf(e.getCause()));
    }

    @Test
    void neverServesThePreviousMetadataPastMaxStaleEvenBetweenRetries() {
        // Retries every minute, but maxStale ends 30 seconds after the second retry
        DiscoveryOptions options = DiscoveryOptions.builder()
                .defaultTtl(TTL).minTtl(MIN_TTL).maxStale(Duration.ofSeconds(90)).build();
        statuses.addAll(List.of(200, 503));
        MetadataResolver resolver = resolver(options);
        resolver.metadata().join();
        advance(TTL);
        resolver.metadata().join();
        advance(MIN_TTL);
        resolver.metadata().join();
        assertEquals(3, requests.get());

        advance(Duration.ofSeconds(30));

        assertThrows(CompletionException.class, () -> resolver.metadata().join(),
                "the previous result must not be served past maxStale while waiting for the next retry");
        assertEquals(4, requests.get());
    }

    @Test
    void neverUsesAnExpiredResultWhenMaxStaleIsZero() {
        DiscoveryOptions options = DiscoveryOptions.builder().defaultTtl(TTL).minTtl(MIN_TTL).maxStale(Duration.ZERO).build();
        statuses.addAll(List.of(200, 503));
        MetadataResolver resolver = resolver(options);
        resolver.metadata().join();
        advance(TTL);

        assertThrows(CompletionException.class, () -> resolver.metadata().join());
    }

    @Test
    void startsAfreshAfterDroppingTheStaleResult() {
        statuses.addAll(List.of(200, 503, 503));
        MetadataResolver resolver = resolver();
        resolver.metadata().join();
        advance(TTL.plus(MAX_STALE));
        assertThrows(CompletionException.class, () -> resolver.metadata().join());

        // Without a previous result, each use tries again and fails
        assertThrows(CompletionException.class, () -> resolver.metadata().join());
        assertEquals(3, requests.get());
    }

    @Test
    void checksA404AfterDroppedStaleMetadataAgainAfterMinTtl() {
        statuses.addAll(List.of(200, 503, 404, 200));
        MetadataResolver resolver = resolver();
        resolver.metadata().join();
        advance(TTL.plus(MAX_STALE));
        assertThrows(CompletionException.class, () -> resolver.metadata().join());

        assertEquals(Optional.empty(), resolver.metadata().join());
        advance(MIN_TTL);

        assertTrue(resolver.metadata().join().isPresent(), "the PDP published metadata before the outage");
        assertEquals(4, requests.get());
    }

    @Test
    void dropsThePreviousMetadataWhenTheRefreshReturnsUnusableMetadata() {
        statuses.addAll(List.of(200, FOREIGN, 503));
        MetadataResolver resolver = resolver();
        resolver.metadata().join();
        advance(TTL);
        assertThrows(CompletionException.class, () -> resolver.metadata().join());

        CompletionException e = assertThrows(CompletionException.class, () -> resolver.metadata().join(),
                "a later outage must not bring back metadata the PDP has replaced");
        assertTrue(e.getCause() instanceof PdpCommunicationException, String.valueOf(e.getCause()));
    }

    @Test
    void failsInsteadOfBlockingWhenTheClockFailsWhileSettling() {
        statuses.addAll(List.of(200));
        AtomicInteger reads = new AtomicInteger();
        MetadataResolver resolver = new MetadataResolver(transport(), identifier, OPTIONS, false, () -> {
            if (reads.incrementAndGet() == 2) {
                throw new IllegalStateException("clock failed");
            }
            return nanos.get();
        });

        CompletionException e = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(CompletionException.class, () -> resolver.metadata().join()));
        assertTrue(e.getCause() instanceof AuthZenException, String.valueOf(e.getCause()));

        // The failed settle released the fetch, so the next call fetches again
        assertTrue(resolver.metadata().join().isPresent());
        assertEquals(2, requests.get());
    }

    @Test
    void returnsAFailedFutureWhenTheClockFailsOnRead() {
        MetadataResolver resolver = new MetadataResolver(transport(), identifier, OPTIONS, false, () -> {
            throw new IllegalStateException("clock failed");
        });

        CompletableFuture<Optional<PdpMetadata>> future = resolver.metadata();

        CompletionException e = assertThrows(CompletionException.class, future::join);
        assertTrue(e.getCause() instanceof AuthZenException, String.valueOf(e.getCause()));
        assertEquals(0, requests.get());
    }

    @Test
    void completesWaitingCallersWhenLoggingFails() {
        statuses.addAll(List.of(404));
        TestLoggerFinder.failing = true;

        // The 404 is logged as a change; a failing logger must not leave the future pending
        Optional<PdpMetadata> metadata = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> resolver().metadata().join());

        assertEquals(Optional.empty(), metadata);
    }

    @Test
    void failsWaitingCallersWhenLoggingFails() {
        statuses.addAll(List.of(503));
        TestLoggerFinder.failing = true;

        CompletionException e = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(CompletionException.class, () -> resolver().metadata().join()));

        assertTrue(e.getCause() instanceof PdpCommunicationException, String.valueOf(e.getCause()));
    }
}
