package io.github.authzenenforcer.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs a real client against a PDP that permits everything. */
class EndToEndTest {

    private HttpServer server;

    @BeforeEach
    void startPdp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/access/v1/evaluation", exchange -> {
            byte[] body = "{\"decision\": true}".getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopPdp() {
        server.stop(0);
    }

    @Test
    void recordsTheCallsOfAClient() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuthZenClient client = AuthZenClient.builder()
                .pdpUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .discovery(false)
                .requestTimeout(Duration.ofSeconds(2))
                .listener(MicrometerPepListener.builder(registry).build())
                .build();
        AuthZenClientMetrics.bind(client, registry);

        assertTrue(client.evaluate(EvaluationRequest.of(Subject.of("user", "alice"), Action.of("can_read"),
                Resource.of("account", "123"))).getDecision());

        // Events arrive on the listener thread
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Search.in(registry).name(MicrometerPepListener.CALLS).timer() == null
                && System.nanoTime() - deadline < 0) {
            Thread.sleep(5);
        }
        assertEquals(1, registry.get(MicrometerPepListener.CALLS).tag("outcome", "permit").timer().count());
        assertEquals(1, registry.get(MicrometerPepListener.ATTEMPTS).tag("status", "200").timer().count());
        assertEquals(0, registry.get(AuthZenClientMetrics.LISTENER_DROPPED).tag("event", "call_completed")
                .functionCounter().count());
    }
}
