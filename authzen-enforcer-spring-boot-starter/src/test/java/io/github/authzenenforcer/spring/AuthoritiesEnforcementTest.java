package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.spring.app.TestApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The caller's granted authorities as roles in a subject property, in Spring MVC.
 */
@SpringBootTest(classes = {TestApplication.class, AuthoritiesEnforcementTest.Security.class,
        AuthoritiesEnforcementTest.Accounts.class})
@AutoConfigureMockMvc
class AuthoritiesEnforcementTest {

    private static final FakePdp PDP = new FakePdp();

    @Autowired
    private MockMvc mvc;

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
    void resetPdp() {
        PDP.reset();
    }

    private static RequestPostProcessor alice() {
        return jwt().jwt(token -> token.subject("alice").claim("department", "sales"))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"), new SimpleGrantedAuthority("SCOPE_read"),
                        new SimpleGrantedAuthority("ROLE_auditor"));
    }

    @Test
    void sendsTheRolesAmongTheAuthoritiesUnderTheirFlatName() throws Exception {
        mvc.perform(get("/accounts/7").with(alice()))
                .andExpect(status().isOk());

        assertEquals(Map.of("type", "user", "id", "alice", "properties",
                        Map.of("department", "sales", "cerbos.roles", List.of("admin", "auditor"))),
                PDP.lastRequest().get("subject"));
    }

    @Test
    void leavesThePropertyOutWithoutRoles() throws Exception {
        mvc.perform(get("/accounts/7").with(jwt().jwt(token -> token.subject("bob"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_read"))))
                .andExpect(status().isOk());

        assertEquals(Map.of("type", "user", "id", "bob"), PDP.lastRequest().get("subject"));
    }

    @Test
    void keepsTheAuthoritiesOutOfTheClaimsOfExpressions() throws Exception {
        mvc.perform(get("/accounts/7").with(alice()))
                .andExpect(status().isOk());

        Map<String, Object> context = new HashMap<>();
        context.put("hidden", null);
        assertEquals(context, PDP.lastRequest().get("context"));
    }

    @Test
    void givesTheApplicationTheSameSubject() throws Exception {
        mvc.perform(get("/whoami").with(alice()))
                .andExpect(status().isOk())
                .andExpect(content().string("alice {department=sales, cerbos.roles=[admin, auditor]}"));

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
        String account(@PathVariable String id) {
            return "account " + id;
        }

        @GetMapping("/whoami")
        String whoami(Authentication caller) {
            return subjects.of(caller).map(subject -> subject.getId() + " " + subject.getProperties())
                    .orElse("anonymous");
        }
    }

    @TestConfiguration
    static class Security {

        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                    .csrf(csrf -> csrf.disable())
                    .build();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("Tokens are set by the tests");
            };
        }
    }
}
