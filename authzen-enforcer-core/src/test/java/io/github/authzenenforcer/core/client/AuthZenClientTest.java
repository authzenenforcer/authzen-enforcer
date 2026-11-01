package io.github.authzenenforcer.core.client;

import static io.github.authzenenforcer.core.client.FakePdp.json;
import static io.github.authzenenforcer.core.client.FakePdp.raw;
import static io.github.authzenenforcer.core.client.FakePdp.sequence;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpAuthenticationException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.PdpProtocolException;
import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.FakePdp.RecordedRequest;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthZenClientTest {

    private static final String TOKEN = "token-1";
    private static final EvaluationRequest REQUEST = EvaluationRequest.of(
            Subject.of("user", "alice@example.com"), Action.of("can_read"), Resource.of("account", "123"));

    private FakePdp pdp;

    @BeforeEach
    void startPdp() throws IOException {
        pdp = FakePdp.start();
    }

    @AfterEach
    void stopPdp() {
        pdp.close();
    }

    private AuthZenClient client() {
        return AuthZenClient.builder()
                .pdpUrl(pdp.url())
                .credentials(CredentialProvider.staticBearer(TOKEN))
                .requestTimeout(Duration.ofSeconds(2))
                .build();
    }

    private RecordedRequest onlyRequest() {
        List<RecordedRequest> requests = pdp.requests();
        assertEquals(1, requests.size());
        return requests.get(0);
    }

    @Nested
    class Requests {

        @Test
        void postsTheEvaluationToTheDefaultPath() {
            client().evaluate(REQUEST);

            RecordedRequest request = onlyRequest();
            assertEquals("POST", request.method());
            assertEquals("/access/v1/evaluation", request.path());
            assertEquals("application/json", request.header("Content-Type"));
            assertEquals("application/json", request.header("Accept"));
            assertTrue(request.header("User-Agent").startsWith("authzen-enforcer"));
            assertEquals("Bearer " + TOKEN, request.header("Authorization"));
        }

        @Test
        void sendsTheRequestInTheWireFormat() {
            client().evaluate(REQUEST);

            assertEquals(Map.of(
                    "subject", Map.of("type", "user", "id", "alice@example.com"),
                    "action", Map.of("name", "can_read"),
                    "resource", Map.of("type", "account", "id", "123")),
                    JsonCodec.defaultCodec().read(onlyRequest().body()));
        }

        @Test
        void sendsAFreshUuidAsRequestIdPerRequest() {
            AuthZenClient client = client();
            client.evaluate(REQUEST);
            client.evaluate(REQUEST);

            List<RecordedRequest> requests = pdp.requests();
            String first = requests.get(0).header("X-Request-ID");
            String second = requests.get(1).header("X-Request-ID");
            assertEquals(first, UUID.fromString(first).toString());
            assertNotEquals(first, second);
        }

        @Test
        void appendsTheDefaultPathToThePdpUrlPath() {
            AuthZenClient.builder().pdpUrl(pdp.url() + "/tenants/acme/").build().evaluate(REQUEST);

            assertEquals("/tenants/acme/access/v1/evaluation", onlyRequest().path());
        }

        @Test
        void usesTheConfiguredEvaluationEndpoint() {
            AuthZenClient.builder()
                    .pdpUrl(pdp.url())
                    .evaluationEndpoint(URI.create(pdp.url() + "/custom/evaluate"))
                    .build()
                    .evaluate(REQUEST);

            assertEquals("/custom/evaluate", onlyRequest().path());
        }

        @Test
        void sendsNoAuthorizationWithoutCredentials() {
            AuthZenClient.builder().pdpUrl(pdp.url()).build().evaluate(REQUEST);

            assertNull(onlyRequest().header("Authorization"));
        }

        @Test
        void asksTheCredentialProviderForEveryRequest() {
            AtomicInteger calls = new AtomicInteger();
            AuthZenClient client = AuthZenClient.builder()
                    .pdpUrl(pdp.url())
                    .credentials(CredentialProvider.bearer(() -> "token-" + calls.incrementAndGet()))
                    .build();

            client.evaluate(REQUEST);
            client.evaluate(REQUEST);

            assertEquals("Bearer token-1", pdp.requests().get(0).header("Authorization"));
            assertEquals("Bearer token-2", pdp.requests().get(1).header("Authorization"));
        }
    }

    @Nested
    class Responses {

        @Test
        void returnsPermitAndDeny() {
            pdp.respond(json(200, "{\"decision\": true}"));
            assertTrue(client().evaluate(REQUEST).getDecision());

            pdp.respond(json(200, "{\"decision\": false}"));
            assertFalse(client().evaluate(REQUEST).getDecision());
        }

        @Test
        void returnsTheResponseContext() {
            pdp.respond(json(200, "{\"decision\": false, \"context\": {\"reason_user\": {\"403\": \"No\"}}}"));

            EvaluationResponse response = client().evaluate(REQUEST);

            assertEquals(Context.of(Map.of("reason_user", Map.of("403", "No"))), response.getContext().orElseThrow());
        }

        @Test
        void acceptsAResponseWithoutRequestId() {
            pdp.respond(raw(200, "{\"decision\": true}".getBytes(StandardCharsets.UTF_8)));

            assertTrue(client().evaluate(REQUEST).getDecision());
        }

        @Test
        void evaluatesAsynchronously() {
            pdp.respond(json(200, "{\"decision\": true}"));

            assertTrue(client().evaluateAsync(REQUEST).join().getDecision());
        }
    }

    @Nested
    class FailClosed {

        @ParameterizedTest
        @ValueSource(ints = {204, 302, 400, 404, 405, 409, 415, 422, 429})
        void treatsUnexpectedStatusesAsProtocolErrors(int status) {
            pdp.respond(json(status, status == 204 ? "" : "{\"decision\": true}"));

            assertThrows(PdpProtocolException.class, () -> client().evaluate(REQUEST));
        }

        @ParameterizedTest
        @ValueSource(ints = {500, 502, 503, 504})
        void treatsServerErrorsAsCommunicationErrors(int status) {
            pdp.respond(json(status, "{\"decision\": true}"));

            assertThrows(PdpCommunicationException.class, () -> client().evaluate(REQUEST));
        }

        @Test
        void treatsForbiddenAsAuthenticationErrorWithoutRetry() {
            pdp.respond(json(403, "{\"decision\": true}"));

            PdpAuthenticationException e = assertThrows(PdpAuthenticationException.class, () -> client().evaluate(REQUEST));
            assertEquals(403, e.getStatusCode());
            assertEquals(1, pdp.requests().size());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "not json", "[]", "{}", "{\"decision\": \"true\"}", "{\"decision\": true, \"decision\": false}"})
        void treatsInvalidBodiesAsProtocolErrors(String body) {
            pdp.respond(json(200, body));

            assertThrows(PdpProtocolException.class, () -> client().evaluate(REQUEST));
        }

        @Test
        void rejectsBodiesThatAreNotUtf8() {
            pdp.respond(raw(200, new byte[] {'{', (byte) 0xFF, '}'}));

            assertThrows(PdpProtocolException.class, () -> client().evaluate(REQUEST));
        }

        @Test
        void rejectsAResponseForAnotherRequestId() {
            pdp.respond((request, exchange) -> FakePdp.send(exchange, 200,
                    "{\"decision\": true}".getBytes(StandardCharsets.UTF_8), "other-request"));

            assertThrows(PdpProtocolException.class, () -> client().evaluate(REQUEST));
        }

        @Test
        void rejectsOversizedBodies() {
            byte[] body = new byte[8 * 1024 * 1024 + 1];
            java.util.Arrays.fill(body, (byte) ' ');
            pdp.respond(raw(200, body));

            assertThrows(PdpProtocolException.class, () -> client().evaluate(REQUEST));
        }

        @Test
        void failsWhenThePdpDoesNotAnswerInTime() {
            pdp.respond((request, exchange) -> {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            AuthZenClient client = AuthZenClient.builder().pdpUrl(pdp.url()).requestTimeout(Duration.ofMillis(200)).build();

            assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));
        }

        @Test
        void failsWhenThePdpIsUnreachable() {
            URI url = pdp.url();
            pdp.close();

            AuthZenClient client = AuthZenClient.builder().pdpUrl(url).build();

            assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));
        }

        @Test
        void doesNotFollowRedirects() throws IOException {
            try (FakePdp other = FakePdp.start()) {
                pdp.respond((request, exchange) -> {
                    exchange.getResponseHeaders().set("Location", other.url() + "/access/v1/evaluation");
                    exchange.sendResponseHeaders(307, -1);
                });

                assertThrows(PdpProtocolException.class, () -> client().evaluate(REQUEST));
                assertTrue(other.requests().isEmpty());
            }
        }

        @Test
        void failsWithoutSendingWhenTheCredentialProviderFails() {
            AuthZenClient client = AuthZenClient.builder()
                    .pdpUrl(pdp.url())
                    .credentials(() -> {
                        throw new IllegalStateException("token endpoint down");
                    })
                    .build();

            AuthZenException e = assertThrows(AuthZenException.class, () -> client.evaluate(REQUEST));
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertTrue(pdp.requests().isEmpty());
        }

        @Test
        void failsWithoutSendingWhenTheCredentialProviderReturnsNull() {
            AuthZenClient client = AuthZenClient.builder().pdpUrl(pdp.url()).credentials(() -> null).build();

            assertThrows(AuthZenException.class, () -> client.evaluate(REQUEST));
            assertTrue(pdp.requests().isEmpty());
        }

        @Test
        void completesTheAsyncFutureExceptionally() {
            pdp.respond(json(503, ""));

            CompletionException e = assertThrows(CompletionException.class, () -> client().evaluateAsync(REQUEST).join());
            assertInstanceOf(PdpCommunicationException.class, e.getCause());
        }

        @Test
        void neverPutsTheTokenIntoErrorMessages() {
            pdp.respond(json(401, ""));

            AuthZenException e = assertThrows(AuthZenException.class, () -> client().evaluate(REQUEST));
            assertFalse(e.getMessage().contains(TOKEN));
        }
    }

    @Nested
    class Unauthorized {

        /** Hands out token-1, token-2, ... and moves on when its current token is invalidated. */
        final class RotatingProvider implements CredentialProvider {

            private final AtomicInteger generation = new AtomicInteger(1);
            private final List<AccessCredential> invalidated = new CopyOnWriteArrayList<>();
            private volatile AccessCredential current = AccessCredential.bearer("token-1");

            @Override
            public AccessCredential getCredential() {
                return current;
            }

            @Override
            public void invalidate(AccessCredential rejected) {
                invalidated.add(rejected);
                if (rejected == current) {
                    current = AccessCredential.bearer("token-" + generation.incrementAndGet());
                }
            }
        }

        @Test
        void refreshesTheCredentialAndRetriesOnce() {
            RotatingProvider credentials = new RotatingProvider();
            pdp.respond(sequence(json(401, ""), json(200, "{\"decision\": true}")));
            AuthZenClient client = AuthZenClient.builder().pdpUrl(pdp.url()).credentials(credentials).build();

            assertTrue(client.evaluate(REQUEST).getDecision());

            List<RecordedRequest> requests = pdp.requests();
            assertEquals(2, requests.size());
            assertEquals("Bearer token-1", requests.get(0).header("Authorization"));
            assertEquals("Bearer token-2", requests.get(1).header("Authorization"));
            assertNotEquals(requests.get(0).header("X-Request-ID"), requests.get(1).header("X-Request-ID"));
            assertEquals(1, credentials.invalidated.size());
            assertEquals("token-1", ((AccessCredential.Bearer) credentials.invalidated.get(0)).getToken());
        }

        @Test
        void givesUpAfterTheSecondRejection() {
            RotatingProvider credentials = new RotatingProvider();
            pdp.respond(json(401, ""));
            AuthZenClient client = AuthZenClient.builder().pdpUrl(pdp.url()).credentials(credentials).build();

            PdpAuthenticationException e = assertThrows(PdpAuthenticationException.class, () -> client.evaluate(REQUEST));

            assertEquals(401, e.getStatusCode());
            assertEquals(2, pdp.requests().size());
            assertEquals(1, credentials.invalidated.size());
        }

        @Test
        void retriesAsynchronouslyToo() {
            RotatingProvider credentials = new RotatingProvider();
            pdp.respond(sequence(json(401, ""), json(200, "{\"decision\": false}")));
            AuthZenClient client = AuthZenClient.builder().pdpUrl(pdp.url()).credentials(credentials).build();

            assertFalse(client.evaluateAsync(REQUEST).join().getDecision());
            assertEquals(2, pdp.requests().size());
        }

        @Test
        void doesNotRetryWithoutCredentials() {
            pdp.respond(json(401, ""));
            AuthZenClient client = AuthZenClient.builder().pdpUrl(pdp.url()).build();

            assertThrows(PdpAuthenticationException.class, () -> client.evaluate(REQUEST));
            assertEquals(1, pdp.requests().size());
        }

        @Test
        void failsWhenInvalidationFails() {
            pdp.respond(json(401, ""));
            AuthZenClient client = AuthZenClient.builder()
                    .pdpUrl(pdp.url())
                    .credentials(new CredentialProvider() {
                        @Override
                        public AccessCredential getCredential() {
                            return AccessCredential.bearer(TOKEN);
                        }

                        @Override
                        public void invalidate(AccessCredential rejected) {
                            throw new IllegalStateException("cannot refresh");
                        }
                    })
                    .build();

            AuthZenException e = assertThrows(AuthZenException.class, () -> client.evaluate(REQUEST));
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertEquals(1, pdp.requests().size());
        }
    }

    @Nested
    class Retries {

        private final FakePdp.Responder permit = json(200, "{\"decision\": true}");

        /** Closes the connection without answering, like a server dropping a pooled connection. */
        private final FakePdp.Responder drop = (request, exchange) -> {
        };

        private FakePdp.Responder status(int status, String retryAfter) {
            return (request, exchange) -> {
                exchange.getResponseHeaders().set("Retry-After", retryAfter);
                FakePdp.send(exchange, status, new byte[0], request.header("X-Request-ID"));
            };
        }

        @Test
        void retriesTransientStatusesUntilTheyPass() {
            pdp.respond(sequence(json(503, ""), json(502, ""), json(504, ""), permit));

            assertTrue(client().evaluate(REQUEST).getDecision());
            assertEquals(4, pdp.requests().size());
        }

        @Test
        void givesUpAfterThreeRetriesByDefault() {
            pdp.respond(json(503, ""));

            assertThrows(PdpCommunicationException.class, () -> client().evaluate(REQUEST));
            assertEquals(4, pdp.requests().size());
        }

        @Test
        void honoursTheConfiguredNumberOfRetries() {
            pdp.respond(json(503, ""));
            AuthZenClient once = AuthZenClient.builder().pdpUrl(pdp.url()).maxRetries(1).build();
            AuthZenClient never = AuthZenClient.builder().pdpUrl(pdp.url()).maxRetries(0).build();

            assertThrows(PdpCommunicationException.class, () -> once.evaluate(REQUEST));
            assertEquals(2, pdp.requests().size());
            assertThrows(PdpCommunicationException.class, () -> never.evaluate(REQUEST));
            assertEquals(3, pdp.requests().size());
        }

        @Test
        void retriesDroppedConnections() {
            pdp.respond(sequence(drop, drop, permit));

            assertTrue(client().evaluate(REQUEST).getDecision());
            assertEquals(3, pdp.requests().size());
        }

        @Test
        void retriesTooManyRequests() {
            pdp.respond(sequence(json(429, ""), permit));

            assertTrue(client().evaluate(REQUEST).getDecision());
            assertEquals(2, pdp.requests().size());
        }

        @ParameterizedTest
        @ValueSource(ints = {400, 403, 404, 500, 501})
        void doesNotRetryPermanentFailures(int status) {
            pdp.respond(json(status, ""));

            assertThrows(AuthZenException.class, () -> client().evaluate(REQUEST));
            assertEquals(1, pdp.requests().size());
        }

        @Test
        void doesNotRetryInvalidResponses() {
            pdp.respond(json(200, "not json"));

            assertThrows(PdpProtocolException.class, () -> client().evaluate(REQUEST));
            assertEquals(1, pdp.requests().size());
        }

        @Test
        void doesNotRetryRequestTimeouts() {
            pdp.respond((request, exchange) -> {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            AuthZenClient client = AuthZenClient.builder().pdpUrl(pdp.url()).requestTimeout(Duration.ofMillis(200)).build();

            assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));
            assertEquals(1, pdp.requests().size());
        }

        @Test
        void sendsAFreshRequestIdPerAttempt() {
            pdp.respond(sequence(json(503, ""), permit));

            client().evaluate(REQUEST);

            List<RecordedRequest> requests = pdp.requests();
            assertNotEquals(requests.get(0).header("X-Request-ID"), requests.get(1).header("X-Request-ID"));
        }

        @Test
        void waitsAsLongAsRetryAfterAsks() {
            pdp.respond(sequence(status(503, "1"), permit));
            long start = System.nanoTime();

            assertTrue(client().evaluate(REQUEST).getDecision());

            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(1)) >= 0);
            assertEquals(2, pdp.requests().size());
        }

        @Test
        void doesNotRetryWhenRetryAfterAsksForTooLong() {
            pdp.respond(status(503, "30"));

            assertThrows(PdpCommunicationException.class, () -> client().evaluate(REQUEST));
            assertEquals(1, pdp.requests().size());
        }

        @Test
        void startsNoRetryOnceTheRequestTimeoutHasPassed() {
            pdp.respond((request, exchange) -> {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                FakePdp.send(exchange, 503, new byte[0], request.header("X-Request-ID"));
            });
            AuthZenClient client = AuthZenClient.builder().pdpUrl(pdp.url()).requestTimeout(Duration.ofMillis(400)).build();

            assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));
            assertTrue(pdp.requests().size() <= 2);
        }

        @Test
        void refreshesTheCredentialAndRetriesTransientFailuresInTheSameCall() {
            Unauthorized unauthorized = new Unauthorized();
            CredentialProvider credentials = unauthorized.new RotatingProvider();
            pdp.respond(sequence(json(401, ""), json(503, ""), permit));
            AuthZenClient client = AuthZenClient.builder().pdpUrl(pdp.url()).credentials(credentials).build();

            assertTrue(client.evaluateAsync(REQUEST).join().getDecision());

            List<RecordedRequest> requests = pdp.requests();
            assertEquals(3, requests.size());
            assertEquals("Bearer token-2", requests.get(2).header("Authorization"));
        }
    }

    @Test
    void returnsTheSameExceptionInstanceFromTheSyncApi() {
        pdp.respond(json(500, ""));
        AuthZenClient client = client();

        PdpCommunicationException e = assertThrows(PdpCommunicationException.class, () -> client.evaluate(REQUEST));
        assertSame(PdpCommunicationException.class, e.getClass());
        assertTrue(e.getMessage().contains("X-Request-ID"));
    }
}
