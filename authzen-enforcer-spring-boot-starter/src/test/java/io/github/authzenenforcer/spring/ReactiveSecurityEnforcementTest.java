package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.spring.app.TestApplication;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(classes = {TestApplication.class, ReactiveSecurityEnforcementTest.Security.class,
        ReactiveSecurityEnforcementTest.Reactive.class},
        properties = "spring.main.web-application-type=reactive")
class ReactiveSecurityEnforcementTest {

    private static final FakePdp PDP = new FakePdp();

    private WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("authzen.pdp.url", PDP::url);
        registry.add("authzen.enforcer.subject.properties.roles", () -> "realm_access.roles");
    }

    @AfterAll
    static void stopPdp() {
        PDP.close();
    }

    @BeforeEach
    void setUp(@Autowired ApplicationContext context) {
        PDP.reset();
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
    }

    private static JwtMutator alice() {
        return mockJwt().jwt(token -> token.subject("alice")
                .claim("realm_access", Map.of("roles", List.of("editor")))
                .claim("email", "alice@example.com"));
    }

    @Test
    void permitsAnAnnotatedHandlerWithTheInferredRequest() {
        client.mutateWith(alice()).get().uri("/documents/42").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("document 42");

        Map<String, Object> request = PDP.lastRequest();
        assertEquals(Map.of("type", "user", "id", "alice", "properties", Map.of("roles", List.of("editor"))),
                request.get("subject"));
        assertEquals(Map.of("name", "read"), request.get("action"));
        assertEquals(Map.of("type", "documents", "id", "42"), request.get("resource"));
    }

    @Test
    void deniesWithoutRunningTheHandler() {
        PDP.decide(request -> false);

        client.mutateWith(alice()).get().uri("/documents/42").exchange()
                .expectStatus().isForbidden()
                .expectBody(String.class).value(body -> assertTrue(body == null || !body.contains("document")));
    }

    @Test
    void leavesAnonymousCallersToTheEntryPointWithoutAskingThePdp() {
        client.get().uri("/documents/42").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value("WWW-Authenticate", value -> assertTrue(value.startsWith("Bearer")));

        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void leavesHandlersWithoutAnnotationOpen() {
        client.get().uri("/documents/42/preview").exchange()
                .expectStatus().isOk();

        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void evaluatesExpressionsOfTheAnnotation() {
        client.mutateWith(alice()).post().uri("/orders/7/cancel?access_token=t-1")
                .header("User-Agent", "test-agent")
                .cookie("SESSION", "s-1")
                .exchange()
                .expectStatus().isOk();

        Map<String, Object> request = PDP.lastRequest();
        assertEquals(Map.of("name", "cancel"), request.get("action"));
        assertEquals(Map.of("type", "order", "id", "7"), request.get("resource"));
        Map<String, Object> context = new HashMap<>();
        // A mock exchange has no remote address
        context.put("ip", null);
        context.put("caller", "alice");
        context.put("agent", "test-agent");
        // Credentials stay out of reach of expressions
        context.put("auth", null);
        context.put("token", null);
        assertEquals(context, request.get("context"));
    }

    @Test
    void checksAResourceIdThatAnExpressionGives() {
        client.mutateWith(alice()).post().uri("/notes/share?ref=n-1").exchange().expectStatus().isOk();
        assertEquals(Map.of("type", "note", "id", "n-1"), PDP.lastRequest().get("resource"));
        PDP.reset();

        for (String ref : List.of("", "n\u0007", "n".repeat(1025))) {
            client.mutateWith(alice()).post().uri(uri -> uri.path("/notes/share").queryParam("ref", "{ref}").build(ref))
                    .exchange()
                    .expectStatus().isForbidden();
        }
        client.mutateWith(alice()).post().uri("/notes/share").exchange().expectStatus().isForbidden();
        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void readsParametersOfAFormBodyAndLeavesTheFormToTheHandler() {
        client.mutateWith(alice()).post().uri("/forms/share")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("ref", "n-2"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("shared n-2");

        assertEquals(Map.of("type", "note", "id", "n-2"), PDP.lastRequest().get("resource"));
    }

    @Test
    void prefersTheQueryToTheFormAsAServletContainerDoes() {
        client.mutateWith(alice()).post().uri("/forms/share?ref=from-query")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("ref", "from-form"))
                .exchange()
                .expectStatus().isOk();

        assertEquals(Map.of("type", "note", "id", "from-query"), PDP.lastRequest().get("resource"));
    }

    @Test
    void doesNotReadTheBodyOfAHandlerWhoseExpressionsReadNoParameters() {
        // Larger than the codecs read into memory: reading it would fail
        client.mutateWith(alice()).post().uri("/invoices/9/approve")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("ref", "n".repeat(300 * 1024)))
                .exchange()
                .expectStatus().isOk();

        assertEquals(Map.of("type", "invoices", "id", "9"), PDP.lastRequest().get("resource"));
    }

    @Test
    void refusesARequestWhoseFormCannotBeReadWithoutAskingThePdp() {
        client.mutateWith(alice()).post().uri("/forms/share")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("ref", "n".repeat(300 * 1024)))
                .exchange()
                .expectStatus().isForbidden();

        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void decidesAReactiveHandlerOnce() {
        client.mutateWith(alice()).get().uri("/streams/5").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("stream 5");

        assertEquals(1, PDP.requests().size());
    }

    @Test
    void replacesOnlyTheTypeOfACollection() {
        client.mutateWith(alice()).get().uri("/papers").exchange().expectStatus().isOk();

        assertEquals(Map.of("name", "list"), PDP.lastRequest().get("action"));
        assertEquals(Map.of("type", "belge", "id", "papers"), PDP.lastRequest().get("resource"));
    }

    @Test
    void supportsMetaAnnotations() {
        client.mutateWith(alice()).post().uri("/invoices/9/approve").exchange().expectStatus().isOk();

        assertEquals(Map.of("name", "approve"), PDP.lastRequest().get("action"));
        assertEquals(Map.of("type", "invoices", "id", "9"), PDP.lastRequest().get("resource"));
    }

    @Test
    void appliesTheClassAnnotationToEveryMethod() {
        client.mutateWith(alice()).get().uri("/reports/3").exchange().expectStatus().isOk();

        Map<String, Object> request = PDP.lastRequest();
        assertEquals(Map.of("name", "read"), request.get("action"));
        assertEquals(Map.of("type", "report", "id", "3"), request.get("resource"));
        assertEquals(Map.of("level", "type"), request.get("context"));
    }

    @Test
    void letsTheMethodAnnotationWinAttributeByAttribute() {
        client.mutateWith(alice()).post().uri("/reports/3/export").exchange().expectStatus().isOk();

        Map<String, Object> request = PDP.lastRequest();
        assertEquals(Map.of("name", "export"), request.get("action"));
        assertEquals(Map.of("level", "method"), request.get("context"));
    }

    @Test
    void skipsAMethodOfAnAnnotatedClass() {
        client.get().uri("/reports/public").exchange().expectStatus().isOk();

        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void deniesWhenThePdpFails() {
        PDP.status(500);

        client.mutateWith(alice()).get().uri("/documents/42").exchange().expectStatus().isForbidden();
    }

    @Test
    void refusesARouteItCannotInferWithoutAskingThePdp() {
        client.mutateWith(alice()).get().uri("/acme/things").exchange().expectStatus().isForbidden();

        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void refusesARequestWhoseExpressionFails() {
        client.mutateWith(alice()).get().uri("/broken/1").exchange().expectStatus().isForbidden();

        assertTrue(PDP.requests().isEmpty());
    }

    /** Handlers of WebFlux only. */
    @RestController
    public static class Reactive {

        @CheckPermission(action = "share", resourceType = "note", resourceId = "#request.parameter('ref')")
        @PostMapping("/forms/share")
        public Mono<String> share(ServerWebExchange exchange) {
            return exchange.getFormData().map(form -> "shared " + form.getFirst("ref"));
        }

        @CheckPermission
        @GetMapping("/streams/{id}")
        public Mono<String> stream(@PathVariable String id) {
            return Mono.fromSupplier(() -> "stream " + id);
        }
    }

    @TestConfiguration
    public static class Security {

        @Bean
        SecurityWebFilterChain filterChain(ServerHttpSecurity http) {
            // Every request reaches the handlers, so that enforcement decides
            return http.authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                    .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .build();
        }

        @Bean
        ReactiveJwtDecoder jwtDecoder() {
            return token -> Mono.error(new BadJwtException("Tokens are set by the tests"));
        }
    }
}
