package io.github.authzenenforcer.core.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final Duration BUDGET = Duration.ofSeconds(5);

    private final RetryPolicy policy = new RetryPolicy(3, BUDGET, Clock.fixed(NOW, ZoneOffset.UTC));

    @Nested
    class Errors {

        @Test
        void retriesConnectionFailures() {
            assertTrue(policy.afterError(0, Duration.ZERO, new ConnectException("refused")).isPresent());
            assertTrue(policy.afterError(0, Duration.ZERO, new HttpConnectTimeoutException("connect")).isPresent());
            assertTrue(policy.afterError(0, Duration.ZERO, new IOException("connection reset")).isPresent());
        }

        @Test
        void doesNotRetryPermanentOrSlowFailures() {
            assertFalse(policy.afterError(0, Duration.ZERO, new HttpTimeoutException("request")).isPresent());
            assertFalse(policy.afterError(0, Duration.ZERO, new SSLHandshakeException("certificate")).isPresent());
            assertFalse(policy.afterError(0, Duration.ZERO, new LimitedBodyHandler.BodyTooLargeException(1)).isPresent());
            assertFalse(policy.afterError(0, Duration.ZERO, new IllegalStateException()).isPresent());
        }

        @Test
        void stopsAfterTheMaximumNumberOfRetries() {
            IOException error = new ConnectException("refused");
            assertTrue(policy.afterError(2, Duration.ZERO, error).isPresent());
            assertFalse(policy.afterError(3, Duration.ZERO, error).isPresent());
            assertFalse(new RetryPolicy(0, BUDGET, Clock.systemUTC()).afterError(0, Duration.ZERO, error).isPresent());
        }

        @Test
        void stopsOnceTheBudgetIsUsedUp() {
            assertFalse(policy.afterError(0, BUDGET, new ConnectException("refused")).isPresent());
        }

        @Test
        void growsTheDelayExponentiallyWithJitter() {
            for (int i = 0; i < 100; i++) {
                assertTrue(delay(0).compareTo(Duration.ofMillis(100)) <= 0);
                assertTrue(delay(1).compareTo(Duration.ofMillis(200)) <= 0);
                assertTrue(delay(2).compareTo(Duration.ofMillis(400)) <= 0);
            }
            RetryPolicy many = new RetryPolicy(10, Duration.ofMinutes(1), Clock.systemUTC());
            for (int i = 0; i < 100; i++) {
                assertTrue(many.afterError(9, Duration.ZERO, new ConnectException()).orElseThrow()
                        .compareTo(RetryPolicy.MAX_DELAY) <= 0);
            }
        }

        private Duration delay(int retries) {
            return policy.afterError(retries, Duration.ZERO, new ConnectException("refused")).orElseThrow();
        }
    }

    @Nested
    class Responses {

        @ParameterizedTest
        @ValueSource(ints = {429, 502, 503, 504})
        void retriesTransientStatuses(int status) {
            assertTrue(policy.afterResponse(0, Duration.ZERO, response(status, Map.of())).isPresent());
        }

        @ParameterizedTest
        @ValueSource(ints = {200, 400, 401, 403, 404, 409, 500, 501, 505})
        void doesNotRetryOtherStatuses(int status) {
            assertFalse(policy.afterResponse(0, Duration.ZERO, response(status, Map.of())).isPresent());
        }

        @Test
        void honoursRetryAfterSeconds() {
            assertEquals(Optional.of(Duration.ofSeconds(1)),
                    policy.afterResponse(0, Duration.ZERO, response(503, Map.of("Retry-After", "1"))));
        }

        @Test
        void honoursRetryAfterDates() {
            assertEquals(Optional.of(Duration.ofSeconds(2)), policy.afterResponse(0, Duration.ZERO,
                    response(429, Map.of("Retry-After", "Sun, 1 Nov 2026 12:00:02 GMT"))));
            assertEquals(Optional.of(Duration.ZERO), policy.afterResponse(0, Duration.ZERO,
                    response(429, Map.of("Retry-After", "Sun, 1 Nov 2026 11:00:00 GMT"))));
        }

        @Test
        void doesNotRetryWhenRetryAfterAsksForTooLong() {
            assertFalse(policy.afterResponse(0, Duration.ZERO, response(503, Map.of("Retry-After", "3"))).isPresent());
            assertFalse(policy.afterResponse(0, Duration.ZERO,
                    response(503, Map.of("Retry-After", "99999999999999999999"))).isPresent());
        }

        @Test
        void fallsBackToBackoffForInvalidRetryAfter() {
            Duration delay = policy.afterResponse(0, Duration.ZERO, response(503, Map.of("Retry-After", "soon")))
                    .orElseThrow();
            assertTrue(delay.compareTo(RetryPolicy.BASE_DELAY) <= 0);
        }

        @Test
        void ignoresRetryAfterOnGatewayErrors() {
            Duration delay = policy.afterResponse(0, Duration.ZERO, response(502, Map.of("Retry-After", "60")))
                    .orElseThrow();
            assertTrue(delay.compareTo(RetryPolicy.BASE_DELAY) <= 0);
        }

        @Test
        void doesNotStartARetryThatEndsAfterTheBudget() {
            assertFalse(policy.afterResponse(0, Duration.ofMillis(4_500), response(503, Map.of("Retry-After", "1")))
                    .isPresent());
        }
    }

    private static HttpResponse<Void> response(int status, Map<String, String> headers) {
        HttpHeaders httpHeaders = HttpHeaders.of(
                headers.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue()))),
                (name, value) -> true);
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                return HttpRequest.newBuilder(URI.create("http://pdp.example.com")).build();
            }

            @Override
            public Optional<HttpResponse<Void>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return httpHeaders;
            }

            @Override
            public Void body() {
                return null;
            }

            @Override
            public Optional<javax.net.ssl.SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("http://pdp.example.com");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
