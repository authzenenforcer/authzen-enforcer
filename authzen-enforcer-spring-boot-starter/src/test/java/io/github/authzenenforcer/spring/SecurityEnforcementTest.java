package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.spring.app.TestApplication;

import jakarta.servlet.http.Cookie;

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
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {TestApplication.class, SecurityEnforcementTest.Security.class})
@AutoConfigureMockMvc
class SecurityEnforcementTest {

    private static final FakePdp PDP = new FakePdp();

    @Autowired
    private MockMvc mvc;

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
    void resetPdp() {
        PDP.reset();
    }

    private static RequestPostProcessor alice() {
        return jwt().jwt(token -> token.subject("alice")
                .claim("realm_access", Map.of("roles", List.of("editor")))
                .claim("email", "alice@example.com"));
    }

    @Test
    void permitsAnAnnotatedHandlerWithTheInferredRequest() throws Exception {
        mvc.perform(get("/documents/42").with(alice()))
                .andExpect(status().isOk())
                .andExpect(content().string("document 42"));

        Map<String, Object> request = PDP.lastRequest();
        assertEquals(Map.of("type", "user", "id", "alice", "properties", Map.of("roles", List.of("editor"))),
                request.get("subject"));
        assertEquals(Map.of("name", "read"), request.get("action"));
        assertEquals(Map.of("type", "documents", "id", "42"), request.get("resource"));
    }

    @Test
    void deniesWithoutRunningTheHandler() throws Exception {
        PDP.decide(request -> false);

        mvc.perform(get("/documents/42").with(alice()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
    }

    @Test
    void leavesAnonymousCallersToTheEntryPointWithoutAskingThePdp() throws Exception {
        mvc.perform(get("/documents/42"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));

        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void leavesHandlersWithoutAnnotationOpen() throws Exception {
        mvc.perform(get("/documents/42/preview"))
                .andExpect(status().isOk());

        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void evaluatesExpressionsOfTheAnnotation() throws Exception {
        mvc.perform(post("/orders/7/cancel").with(alice()).header("User-Agent", "test-agent")
                        .cookie(new Cookie("SESSION", "s-1")).param("access_token", "t-1"))
                .andExpect(status().isOk());

        Map<String, Object> request = PDP.lastRequest();
        assertEquals(Map.of("name", "cancel"), request.get("action"));
        assertEquals(Map.of("type", "order", "id", "7"), request.get("resource"));
        Map<String, Object> context = new HashMap<>();
        context.put("ip", "127.0.0.1");
        context.put("caller", "alice");
        context.put("agent", "test-agent");
        // Credentials stay out of reach of expressions
        context.put("auth", null);
        context.put("token", null);
        assertEquals(context, request.get("context"));
    }

    @Test
    void checksAResourceIdThatAnExpressionGives() throws Exception {
        mvc.perform(post("/notes/share").with(alice()).param("ref", "n-1")).andExpect(status().isOk());
        assertEquals(Map.of("type", "note", "id", "n-1"), PDP.lastRequest().get("resource"));
        PDP.reset();

        for (String ref : List.of("", "n\u0007", "n".repeat(1025))) {
            mvc.perform(post("/notes/share").with(alice()).param("ref", ref)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/notes/share").with(alice())).andExpect(status().isForbidden());
        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void decidesAnAsynchronousHandlerOnce() throws Exception {
        MvcResult started = mvc.perform(get("/exports/5").with(alice()))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string("export 5"));
        assertEquals(1, PDP.requests().size());
    }

    @Test
    void replacesOnlyTheTypeOfACollection() throws Exception {
        mvc.perform(get("/papers").with(alice())).andExpect(status().isOk());

        assertEquals(Map.of("name", "list"), PDP.lastRequest().get("action"));
        assertEquals(Map.of("type", "belge", "id", "papers"), PDP.lastRequest().get("resource"));
    }

    @Test
    void supportsMetaAnnotations() throws Exception {
        mvc.perform(post("/invoices/9/approve").with(alice()))
                .andExpect(status().isOk());

        assertEquals(Map.of("name", "approve"), PDP.lastRequest().get("action"));
        assertEquals(Map.of("type", "invoices", "id", "9"), PDP.lastRequest().get("resource"));
    }

    @Test
    void appliesTheClassAnnotationToEveryMethod() throws Exception {
        mvc.perform(get("/reports/3").with(alice()))
                .andExpect(status().isOk());

        Map<String, Object> request = PDP.lastRequest();
        assertEquals(Map.of("name", "read"), request.get("action"));
        assertEquals(Map.of("type", "report", "id", "3"), request.get("resource"));
        assertEquals(Map.of("level", "type"), request.get("context"));
    }

    @Test
    void letsTheMethodAnnotationWinAttributeByAttribute() throws Exception {
        mvc.perform(post("/reports/3/export").with(alice()))
                .andExpect(status().isOk());

        Map<String, Object> request = PDP.lastRequest();
        assertEquals(Map.of("name", "export"), request.get("action"));
        assertEquals(Map.of("type", "report", "id", "3"), request.get("resource"));
        assertEquals(Map.of("level", "method"), request.get("context"));
    }

    @Test
    void skipsAMethodOfAnAnnotatedClass() throws Exception {
        mvc.perform(get("/reports/public"))
                .andExpect(status().isOk());

        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void deniesWhenThePdpFails() throws Exception {
        PDP.status(500);

        mvc.perform(get("/documents/42").with(alice()))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesARouteItCannotInferWithoutAskingThePdp() throws Exception {
        mvc.perform(get("/acme/things").with(alice()))
                .andExpect(status().isForbidden());

        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void refusesARequestWhoseExpressionFails() throws Exception {
        mvc.perform(get("/broken/1").with(alice()))
                .andExpect(status().isForbidden());

        assertTrue(PDP.requests().isEmpty());
    }

    @TestConfiguration
    public static class Security {

        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            // Every request reaches the handlers, so that enforcement decides
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
