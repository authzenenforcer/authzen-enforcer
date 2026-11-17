package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.spring.app.TestApplication;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/** Enforcement of every WebFlux handler, those of Spring Boot Actuator included. */
@SpringBootTest(classes = {TestApplication.class, ReactiveSecurityEnforcementTest.Security.class}, properties = {
        "spring.main.web-application-type=reactive",
        "authzen.enforcer.enforce-all=true",
        "authzen.enforcer.exclude-paths=/actuator/health/**",
        "management.endpoints.web.exposure.include=health,info"})
class ReactiveEnforceAllTest {

    private static final FakePdp PDP = new FakePdp();

    private WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("authzen.pdp.url", PDP::url);
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

    @Test
    void enforcesHandlersWithoutAnnotation() {
        PDP.decide(request -> false);

        client.mutateWith(mockJwt()).get().uri("/documents/42/preview").exchange().expectStatus().isForbidden();
        // A single static segment after the id is a nested collection for GET
        assertEquals(Map.of("type", "collection", "id", "preview", "properties", Map.of(
                "parent", Map.of("type", "documents", "id", "42"),
                "ancestors", List.of(Map.of("type", "documents", "id", "42")))), PDP.lastRequest().get("resource"));
    }

    @Test
    void enforcesActuatorEndpoints() {
        client.get().uri("/actuator/info").exchange().expectStatus().isUnauthorized();

        client.mutateWith(mockJwt()).get().uri("/actuator/info").exchange().expectStatus().isOk();
        assertEquals(Map.of("type", "collection", "id", "info"), PDP.lastRequest().get("resource"));
    }

    @Test
    void leavesExcludedActuatorEndpointsOpen() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();

        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void leavesOptionsRequestsThatSpringAnswersOpen() {
        client.options().uri("/documents/42").exchange().expectStatus().isOk();

        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void stillSkipsAnnotatedHandlers() {
        client.get().uri("/reports/public").exchange().expectStatus().isOk();

        assertTrue(PDP.requests().isEmpty());
    }
}
