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
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * The caller's granted authorities as roles in a subject property, in Spring WebFlux.
 */
@SpringBootTest(classes = {TestApplication.class, ReactiveAuthoritiesEnforcementTest.Security.class,
        ReactiveAuthoritiesEnforcementTest.Accounts.class},
        properties = "spring.main.web-application-type=reactive")
class ReactiveAuthoritiesEnforcementTest {

    private static final FakePdp PDP = new FakePdp();

    private WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("authzen.pdp.url", PDP::url);
        registry.add("authzen.enforcer.subject.properties.department", () -> "department");
        registry.add("authzen.enforcer.subject.authorities.property", () -> "cerbos.roles");
        registry.add("authzen.enforcer.subject.authorities.prefix", () -> "ROLE_");
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
        return mockJwt().jwt(token -> token.subject("alice").claim("department", "sales"))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"), new SimpleGrantedAuthority("SCOPE_read"),
                        new SimpleGrantedAuthority("ROLE_auditor"));
    }

    @Test
    void sendsTheRolesAmongTheAuthoritiesUnderTheirFlatName() {
        client.mutateWith(alice()).get().uri("/accounts/7").exchange()
                .expectStatus().isOk();

        assertEquals(Map.of("type", "user", "id", "alice", "properties",
                        Map.of("department", "sales", "cerbos.roles", List.of("admin", "auditor"))),
                PDP.lastRequest().get("subject"));
    }

    @Test
    void leavesThePropertyOutWithoutRoles() {
        client.mutateWith(mockJwt().jwt(token -> token.subject("bob"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_read")))
                .get().uri("/accounts/7").exchange()
                .expectStatus().isOk();

        assertEquals(Map.of("type", "user", "id", "bob"), PDP.lastRequest().get("subject"));
    }

    @Test
    void keepsTheAuthoritiesOutOfTheClaimsOfExpressions() {
        client.mutateWith(alice()).get().uri("/accounts/7").exchange()
                .expectStatus().isOk();

        Map<String, Object> context = new HashMap<>();
        context.put("hidden", null);
        assertEquals(context, PDP.lastRequest().get("context"));
    }

    @Test
    void givesTheApplicationTheSameSubject() {
        client.mutateWith(alice()).get().uri("/whoami").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("alice {department=sales, cerbos.roles=[admin, auditor]}");

        assertTrue(PDP.requests().isEmpty());
    }

    @RestController
    static class Accounts {

        private final CallerSubjects subjects;

        Accounts(CallerSubjects subjects) {
            this.subjects = subjects;
        }

        @CheckPermission(resourceType = "account", resourceId = "#id",
                context = "hidden=#claims['authzen.enforcer.granted-authorities']")
        @GetMapping("/accounts/{id}")
        Mono<String> account(@PathVariable String id) {
            return Mono.just("account " + id);
        }

        @GetMapping("/whoami")
        Mono<String> whoami(Authentication caller) {
            return Mono.just(subjects.of(caller).map(subject -> subject.getId() + " " + subject.getProperties())
                    .orElse("anonymous"));
        }
    }

    @TestConfiguration
    static class Security {

        @Bean
        SecurityWebFilterChain filterChain(ServerHttpSecurity http) {
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
