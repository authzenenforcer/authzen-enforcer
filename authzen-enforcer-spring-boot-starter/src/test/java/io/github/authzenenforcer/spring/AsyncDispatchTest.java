package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.spring.app.TestApplication;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A request that reaches a handler first through an asynchronous dispatch is enforced. */
@SpringBootTest(classes = {TestApplication.class, SecurityEnforcementTest.Security.class,
        AsyncDispatchTest.Suspending.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncDispatchTest {

    private static final FakePdp PDP = new FakePdp();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("authzen.pdp.url", PDP::url);
    }

    @AfterAll
    static void stopPdp() {
        PDP.close();
    }

    @Test
    void enforcesAHandlerThatAnAsyncDispatchReachesFirst() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/documents/42")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode(), response.body());
    }

    @TestConfiguration
    static class Suspending {

        // Like a throttling filter: suspends the request and resumes it with an asynchronous dispatch
        @Bean
        FilterRegistrationBean<Filter> suspendingFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                if (request.getDispatcherType() == DispatcherType.REQUEST) {
                    request.startAsync().dispatch();
                } else {
                    chain.doFilter(request, response);
                }
            });
            registration.setAsyncSupported(true);
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
