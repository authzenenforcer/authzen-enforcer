package io.github.authzenenforcer.spring;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** A PDP on the loopback interface that records evaluation requests and answers them. */
final class FakePdp implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<Map<String, Object>> requests = new ArrayList<>();
    private Predicate<Map<String, Object>> decision = request -> true;
    private int status = 200;

    FakePdp() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Without metadata, the client uses the default paths
        server.createContext("/", exchange -> respond(exchange, 404, "{}"));
        server.createContext("/access/v1/evaluation", this::evaluate);
        server.start();
    }

    String url() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    synchronized void decide(Predicate<Map<String, Object>> decision) {
        this.decision = decision;
    }

    synchronized void status(int status) {
        this.status = status;
    }

    synchronized List<Map<String, Object>> requests() {
        return List.copyOf(requests);
    }

    synchronized Map<String, Object> lastRequest() {
        return requests.get(requests.size() - 1);
    }

    synchronized void reset() {
        requests.clear();
        decision = request -> true;
        status = 200;
    }

    @SuppressWarnings("unchecked")
    private void evaluate(HttpExchange exchange) throws IOException {
        Map<String, Object> request = JSON.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
        boolean decided;
        int answer;
        synchronized (this) {
            requests.add(request);
            decided = decision.test(request);
            answer = status;
        }
        respond(exchange, answer, answer == 200 ? "{\"decision\":" + decided + "}" : "{}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
