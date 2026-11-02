package io.github.authzenenforcer.core.internal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.PdpCommunicationException;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Refresh behaviour of {@link MetadataResolver} with a refresh interval much longer than the retry
 * interval, which the public builder cannot set.
 */
class MetadataResolverRefreshTest {

    private static final Duration REFRESH = Duration.ofMillis(100);
    private static final Duration RETRY = Duration.ofMillis(20);

    private HttpServer server;
    private final List<Integer> statuses = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private URI identifier;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                int index = requests.getAndIncrement();
                int status = statuses.get(Math.min(index, statuses.size() - 1));
                byte[] body = status == 200 ? metadata().getBytes(StandardCharsets.UTF_8) : new byte[0];
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

    private MetadataResolver resolver() {
        PdpTransport transport = new PdpTransport(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                null, JsonCodec.defaultCodec(), Duration.ofSeconds(2), 0);
        return new MetadataResolver(transport, identifier, REFRESH, RETRY, false);
    }

    private static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    @Test
    void checksA404ThatReplacesPublishedMetadataAgainAfterTheRetryInterval() throws InterruptedException {
        statuses.addAll(List.of(200, 404, 200));
        MetadataResolver resolver = resolver();

        assertTrue(resolver.metadata().join().isPresent());
        sleep(REFRESH.plusMillis(50));
        assertEquals(Optional.empty(), resolver.metadata().join());
        sleep(RETRY.plusMillis(30));

        assertTrue(resolver.metadata().join().isPresent(), "the 404 must not be kept for the refresh interval");
        assertEquals(3, requests.get());
    }

    @Test
    void keepsA404WithoutPreviousMetadataForTheRefreshInterval() throws InterruptedException {
        statuses.addAll(List.of(404, 200));
        MetadataResolver resolver = resolver();

        assertEquals(Optional.empty(), resolver.metadata().join());
        sleep(RETRY.plusMillis(30));

        assertEquals(Optional.empty(), resolver.metadata().join());
        assertEquals(1, requests.get());
    }

    @Test
    void keepsThePreviousMetadataWhileThePdpRateLimits() throws InterruptedException {
        statuses.addAll(List.of(200, 429));
        MetadataResolver resolver = resolver();
        PdpMetadata first = resolver.metadata().join().orElseThrow();
        sleep(REFRESH.plusMillis(50));

        assertEquals(Optional.of(first), resolver.metadata().join());
        assertEquals(2, requests.get());
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
