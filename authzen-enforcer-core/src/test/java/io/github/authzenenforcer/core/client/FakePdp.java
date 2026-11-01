package io.github.authzenenforcer.core.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A PDP stand-in on top of the JDK HTTP server that records requests and answers as told.
 */
final class FakePdp implements AutoCloseable {

    /** Answers a recorded request. */
    @FunctionalInterface
    interface Responder {
        void respond(RecordedRequest request, HttpExchange exchange) throws IOException;
    }

    /** A request as the PDP received it. Header names are lower case. */
    record RecordedRequest(String method, String path, Map<String, List<String>> headers, String body) {

        String header(String name) {
            List<String> values = headers.get(name.toLowerCase());
            return values == null ? null : values.get(0);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Responder responder = json(200, "{\"decision\": true}");
    private boolean closed;

    private FakePdp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            // HttpExchange is only AutoCloseable from Java 18 on
            try {
                RecordedRequest request = record(exchange);
                requests.add(request);
                responder.respond(request, exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    static FakePdp start() throws IOException {
        return new FakePdp();
    }

    /** Answers with a JSON body and echoes the request id. */
    static Responder json(int status, String body) {
        return (request, exchange) -> send(exchange, status, body.getBytes(StandardCharsets.UTF_8), request.header("X-Request-ID"));
    }

    /** Answers with raw bytes and no request id. */
    static Responder raw(int status, byte[] body) {
        return (request, exchange) -> send(exchange, status, body, null);
    }

    /** Answers each request with the next responder; the last one repeats. */
    static Responder sequence(Responder... responders) {
        List<Responder> list = List.of(responders);
        int[] next = {0};
        return (request, exchange) -> {
            Responder current;
            synchronized (next) {
                current = list.get(Math.min(next[0]++, list.size() - 1));
            }
            current.respond(request, exchange);
        };
    }

    static void send(HttpExchange exchange, int status, byte[] body, String requestId) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (requestId != null) {
            exchange.getResponseHeaders().set("X-Request-ID", requestId);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    void respond(Responder responder) {
        this.responder = responder;
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        server.stop(0);
        executor.shutdownNow();
    }

    private static RecordedRequest record(HttpExchange exchange) throws IOException {
        Map<String, List<String>> headers = exchange.getRequestHeaders().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().toLowerCase(), e -> List.copyOf(e.getValue())));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), headers, body);
    }
}
