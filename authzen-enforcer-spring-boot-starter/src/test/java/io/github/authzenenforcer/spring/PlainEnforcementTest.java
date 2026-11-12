package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.spring.servlet.AuthZenWebMvcAutoConfiguration;
import io.github.authzenenforcer.spring.servlet.ClaimsExtractor;
import io.github.authzenenforcer.spring.servlet.EnforcementResultHandler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Enforcement without Spring Security on the class path. */
class PlainEnforcementTest {

    private static final FakePdp PDP = new FakePdp();

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withClassLoader(new FilteredClassLoader("org.springframework.security"))
            .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class, AuthZenAutoConfiguration.class,
                    AuthZenWebMvcAutoConfiguration.class))
            .withUserConfiguration(Things.class)
            .withPropertyValues("authzen.pdp.url=" + PDP.url(), "authzen.enforcer.enforce-all=true",
                    "authzen.enforcer.exclude-paths=/public/**");

    @AfterAll
    static void stopPdp() {
        PDP.close();
    }

    @BeforeEach
    void resetPdp() {
        PDP.reset();
    }

    @Test
    void answersAnonymousCallersWithAChallengeWithoutAskingThePdp() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get("/things/1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"));
            assertTrue(PDP.requests().isEmpty());
        });
    }

    @Test
    void enforcesEveryHandlerWithClaimsOfTheApplication() {
        runner.withUserConfiguration(Callers.class).run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get("/things/1")).andExpect(status().isOk());
            assertEquals(Map.of("type", "user", "id", "bob"), PDP.lastRequest().get("subject"));
            assertEquals(Map.of("type", "things", "id", "1"), PDP.lastRequest().get("resource"));

            PDP.decide(request -> false);
            mvc.perform(get("/things/1")).andExpect(status().isForbidden());
        });
    }

    @Test
    void leavesExcludedRoutesOpen() {
        runner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get("/public/1")).andExpect(status().isOk());
            assertTrue(PDP.requests().isEmpty());
        });
    }

    @Test
    void answersWithServiceUnavailableWhenConfigured() {
        runner.withUserConfiguration(Callers.class)
                .withPropertyValues("authzen.enforcer.error-status=503")
                .run(context -> {
                    PDP.status(500);
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

                    mvc.perform(get("/things/1")).andExpect(status().isServiceUnavailable());
                });
    }

    @Test
    void letsRequestsThroughWhenFailOpenAndThePdpIsUnavailable() {
        runner.withUserConfiguration(Callers.class)
                .withPropertyValues("authzen.enforcer.fail-open=true")
                .run(context -> {
                    PDP.status(503);
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

                    mvc.perform(get("/things/1")).andExpect(status().isOk());
                });
    }

    @Test
    void doesNotLetAHandlerOfTheApplicationThatSendsNothingThrough() {
        runner.withUserConfiguration(Callers.class)
                .withBean(EnforcementResultHandler.class,
                        () -> (request, response, result) -> { })
                .run(context -> {
                    PDP.decide(request -> false);
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

                    mvc.perform(get("/things/1")).andExpect(status().isForbidden());
                });
    }

    @RestController
    static class Things {

        @GetMapping("/things/{id}")
        String thing(@PathVariable String id) {
            return "thing " + id;
        }

        @GetMapping("/public/{id}")
        String open(@PathVariable String id) {
            return "public " + id;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Callers {

        @Bean
        ClaimsExtractor claimsExtractor() {
            return request -> Optional.of(Map.of("sub", "bob"));
        }
    }
}
