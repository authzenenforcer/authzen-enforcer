package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.event.EnforcementCompleted;
import io.github.authzenenforcer.core.spi.PepListener;
import io.github.authzenenforcer.spring.internal.PermissionHandlerAdapter;
import io.github.authzenenforcer.spring.internal.PermissionInterceptor;
import io.github.authzenenforcer.spring.reactive.AuthZenWebFluxAutoConfiguration;
import io.github.authzenenforcer.spring.reactive.ReactiveClaimsExtractor;
import io.github.authzenenforcer.spring.reactive.ReactiveEnforcementResultHandler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.error.ErrorWebFluxAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Enforcement in Spring WebFlux without Spring Security on the class path. */
class ReactivePlainEnforcementTest {

    private static final FakePdp PDP = new FakePdp();

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withClassLoader(new FilteredClassLoader("org.springframework.security"))
            .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class,
                    ErrorWebFluxAutoConfiguration.class, AuthZenAutoConfiguration.class,
                    AuthZenWebFluxAutoConfiguration.class))
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
        Things.calls.set(0);
    }

    @Test
    void answersAnonymousCallersWithAChallengeWithoutAskingThePdp() {
        runner.run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            client.get().uri("/things/1").exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueEquals("WWW-Authenticate", "Bearer");
            assertTrue(PDP.requests().isEmpty());
            assertEquals(0, Things.calls.get());
        });
    }

    @Test
    void enforcesEveryHandlerWithClaimsOfTheApplication() {
        runner.withUserConfiguration(Callers.class).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            client.get().uri("/things/1").exchange().expectStatus().isOk();
            assertEquals(Map.of("type", "user", "id", "bob"), PDP.lastRequest().get("subject"));
            assertEquals(Map.of("type", "things", "id", "1"), PDP.lastRequest().get("resource"));
            assertEquals(1, Things.calls.get());

            PDP.decide(request -> false);
            client.get().uri("/things/1").exchange().expectStatus().isForbidden();
            assertEquals(1, Things.calls.get());
        });
    }

    @Test
    void leavesExcludedRoutesOpen() {
        runner.run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            client.get().uri("/public/1").exchange().expectStatus().isOk();
            assertTrue(PDP.requests().isEmpty());
        });
    }

    @Test
    void answersOptionsRequestsWithoutAskingThePdp() {
        runner.withUserConfiguration(Callers.class).run(context -> {
            WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

            client.options().uri("/things/1").exchange()
                    .expectStatus().isOk()
                    .expectHeader().exists("Allow");
            assertTrue(PDP.requests().isEmpty());
        });
    }

    @Test
    void answersWithServiceUnavailableWhenConfigured() {
        runner.withUserConfiguration(Callers.class)
                .withPropertyValues("authzen.enforcer.error-status=503")
                .run(context -> {
                    PDP.status(500);
                    WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

                    client.get().uri("/things/1").exchange().expectStatus().isEqualTo(503);
                });
    }

    @Test
    void letsRequestsThroughWhenFailOpenAndThePdpIsUnavailable() {
        runner.withUserConfiguration(Callers.class)
                .withPropertyValues("authzen.enforcer.fail-open=true")
                .run(context -> {
                    PDP.status(503);
                    WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

                    client.get().uri("/things/1").exchange().expectStatus().isOk();
                });
    }

    @Test
    void doesNotLetAHandlerOfTheApplicationThatAnswersNothingThrough() {
        runner.withUserConfiguration(Callers.class)
                .withBean(ReactiveEnforcementResultHandler.class, () -> (exchange, result) -> Mono.empty())
                .run(context -> {
                    PDP.decide(request -> false);
                    WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

                    client.get().uri("/things/1").exchange().expectStatus().isForbidden();
                    assertEquals(0, Things.calls.get());
                });
    }

    @Test
    void refusesWhenTheClaimsExtractorFailsWithoutAskingThePdp() {
        runner.withBean(ReactiveClaimsExtractor.class,
                        () -> exchange -> Mono.error(new IllegalStateException("claims of bob")))
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

                    client.get().uri("/things/1").exchange().expectStatus().isForbidden();
                    assertTrue(PDP.requests().isEmpty());
                });
    }

    @Test
    void startsDecisionsOffTheThreadOfTheRequest() {
        List<String> threads = new CopyOnWriteArrayList<>();
        runner.withUserConfiguration(Callers.class)
                .withBean(CredentialProvider.class, () -> new CredentialProvider() {
                    // Only the blocking method: the default asynchronous one calls it on the caller's thread
                    @Override
                    public AccessCredential getCredential() {
                        threads.add(Thread.currentThread().getName());
                        return AccessCredential.bearer("pep-token");
                    }
                })
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

                    // The first request discovers the PDP's endpoints and then asks for the token on the
                    // thread that completed the discovery
                    client.get().uri("/things/1").exchange().expectStatus().isOk();
                    client.get().uri("/things/2").exchange().expectStatus().isOk();
                    assertEquals(2, threads.size());
                    assertTrue(threads.get(1).startsWith("boundedElastic-"), threads.get(1));
                });
    }

    @Test
    void cancelsTheCallToThePdpWhenTheRequestIsCancelled() {
        List<EnforcementCompleted> completed = new CopyOnWriteArrayList<>();
        runner.withUserConfiguration(Callers.class)
                .withBean(PepListener.class, () -> new PepListener() {
                    @Override
                    public void onEnforcementCompleted(EnforcementCompleted event) {
                        completed.add(event);
                    }
                })
                .run(context -> {
                    PDP.delay(2000);
                    Disposable request = WebHttpHandlerBuilder.applicationContext(context).build()
                            .handle(MockServerHttpRequest.get("/things/1").build(), new MockServerHttpResponse())
                            .subscribe();
                    await(() -> !PDP.requests().isEmpty());

                    request.dispose();

                    await(() -> !completed.isEmpty());
                    assertTrue(completed.get(0).isCancelled());
                    assertEquals(0, Things.calls.get());
                });
    }

    @Test
    void replacesOnlyTheWebFluxConfiguration() {
        runner.run(context -> {
            assertEquals(1, context.getBeansOfType(PermissionHandlerAdapter.class).size());
            assertTrue(context.getBeansOfType(PermissionInterceptor.class).isEmpty());
        });
    }

    @Test
    void worksWithoutTheServletApi() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.security", "jakarta.servlet",
                        "org.springframework.web.servlet"))
                .withUserConfiguration(Callers.class)
                .run(context -> {
                    WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

                    client.get().uri("/things/1").exchange().expectStatus().isOk();
                    assertEquals(1, PDP.requests().size());
                });
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Timed out");
            Thread.sleep(10);
        }
    }

    @RestController
    static class Things {

        static final AtomicInteger calls = new AtomicInteger();

        @GetMapping("/things/{id}")
        Mono<String> thing(@PathVariable String id) {
            calls.incrementAndGet();
            return Mono.just("thing " + id);
        }

        @GetMapping("/public/{id}")
        String open(@PathVariable String id) {
            return "public " + id;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Callers {

        @Bean
        ReactiveClaimsExtractor claimsExtractor() {
            return exchange -> Mono.just(Map.of("sub", "bob"));
        }
    }
}
