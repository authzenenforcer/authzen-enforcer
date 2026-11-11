package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.spring.app.TestApplication;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Enforcement of every handler, those of Spring Boot Actuator included. */
@SpringBootTest(classes = {TestApplication.class, SecurityEnforcementTest.Security.class}, properties = {
        "authzen.enforcer.enforce-all=true",
        "authzen.enforcer.exclude-paths=/actuator/health/**",
        "management.endpoints.web.exposure.include=health,info"})
@AutoConfigureMockMvc
class EnforceAllTest {

    private static final FakePdp PDP = new FakePdp();

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("authzen.pdp.url", PDP::url);
    }

    @AfterAll
    static void stopPdp() {
        PDP.close();
    }

    @BeforeEach
    void resetPdp() {
        PDP.reset();
    }

    @Test
    void enforcesHandlersWithoutAnnotation() throws Exception {
        PDP.decide(request -> false);

        mvc.perform(get("/documents/42/preview").with(jwt())).andExpect(status().isForbidden());
        // A single static segment after the id is a nested collection for GET
        assertEquals(Map.of("type", "collection", "id", "preview", "properties", Map.of(
                "parent", Map.of("type", "documents", "id", "42"),
                "ancestors", List.of(Map.of("type", "documents", "id", "42")))), PDP.lastRequest().get("resource"));
    }

    @Test
    void enforcesActuatorEndpoints() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());

        mvc.perform(get("/actuator/info").with(jwt())).andExpect(status().isOk());
        assertEquals(Map.of("type", "collection", "id", "info"), PDP.lastRequest().get("resource"));
    }

    @Test
    void leavesExcludedActuatorEndpointsOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());

        assertTrue(PDP.requests().isEmpty());
    }

    @Test
    void stillSkipsAnnotatedHandlers() throws Exception {
        mvc.perform(get("/reports/public")).andExpect(status().isOk());

        assertTrue(PDP.requests().isEmpty());
    }
}
