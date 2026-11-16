package io.github.authzenenforcer.spring;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A token endpoint on the loopback interface that issues the tokens t1, t2, ... with the client
 * credentials grant and records the requests.
 */
public final class FakeTokenEndpoint implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Request> requests = new ArrayList<>();
    private int issued;
    private long expiresIn = 300;
    private String tokenType = "Bearer";
    private int status = 200;
    private CountDownLatch entered;
    private CountDownLatch release;

    /** A recorded token request. */
    public record Request(String authorization, String body) {
    }

    /** Starts the endpoint. */
    public FakeTokenEndpoint() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/token", this::token);
        // Requests wait for each other only when a test holds them
        server.setExecutor(executor);
        server.start();
    }

    /**
     * Returns the URL of the token endpoint.
     *
     * @return the URL
     */
    public String tokenUri() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/token";
    }

    /**
     * Sets the lifetime of the tokens issued from now on.
     *
     * @param seconds the lifetime
     */
    public synchronized void expiresIn(long seconds) {
        this.expiresIn = seconds;
    }

    /**
     * Sets the token type of the tokens issued from now on.
     *
     * @param tokenType the token type
     */
    public synchronized void tokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    /**
     * Sets the status of the responses from now on; other than 200, the response is an error.
     *
     * @param status the status
     */
    public synchronized void status(int status) {
        this.status = status;
    }

    /**
     * Holds the next requests until {@link #release()}.
     *
     * @return a latch counted down when a held request arrives
     */
    public synchronized CountDownLatch hold() {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        return entered;
    }

    /** Lets the held requests go on. */
    public synchronized void release() {
        release.countDown();
    }

    /**
     * Returns the recorded requests.
     *
     * @return the requests
     */
    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    private void token(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        CountDownLatch held;
        synchronized (this) {
            requests.add(new Request(exchange.getRequestHeaders().getFirst("Authorization"), body));
            held = release;
            if (entered != null) {
                entered.countDown();
            }
        }
        if (held != null) {
            try {
                held.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String answer;
        int answerStatus;
        synchronized (this) {
            answerStatus = status;
            answer = status == 200
                    ? "{\"access_token\":\"t" + (++issued) + "\",\"token_type\":\"" + tokenType
                            + "\",\"expires_in\":" + expiresIn + "}"
                    : "{\"error\":\"invalid_client\"}";
        }
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(answerStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
