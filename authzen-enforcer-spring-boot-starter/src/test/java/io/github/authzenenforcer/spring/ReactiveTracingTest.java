package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.spring.observability.AuthZenTracingAutoConfiguration;
import io.github.authzenenforcer.spring.reactive.AuthZenWebFluxAutoConfiguration;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.error.ErrorWebFluxAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Traces of PDP calls in Spring WebFlux, where the observation of the request lives in the Reactor
 * Context rather than on the thread.
 */
class ReactiveTracingTest {

    private static final FakePdp PDP = new FakePdp();

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .build();

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class,
                    ErrorWebFluxAutoConfiguration.class, AuthZenAutoConfiguration.class,
                    AuthZenWebFluxAutoConfiguration.class, AuthZenTracingAutoConfiguration.class))
            .withUserConfiguration(ReactivePlainEnforcementTest.Things.class, ReactivePlainEnforcementTest.Callers.class,
                    ObservedRequests.class)
            .withBean(OpenTelemetry.class, () -> openTelemetry)
            .withPropertyValues("authzen.pdp.url=" + PDP.url(), "authzen.enforcer.enforce-all=true");

    @AfterAll
    static void stopPdp() {
        PDP.close();
    }

    @BeforeEach
    void resetPdp() {
        PDP.reset();
    }

    @AfterEach
    void closeOpenTelemetry() {
        openTelemetry.close();
    }

    @Test
    void tracesTheCallInTheTraceOfTheRequest() {
        runner.run(context -> {
            WebTestClient.bindToApplicationContext(context).build()
                    .get().uri("/things/1").exchange().expectStatus().isOk();

            SpanData request = await("request");
            SpanData call = await("authzen evaluation");
            SpanData exchange = await("POST");
            assertEquals(request.getTraceId(), call.getTraceId());
            assertEquals(request.getSpanId(), call.getParentSpanId());
            assertEquals(call.getSpanId(), exchange.getParentSpanId());
            assertEquals("00-" + request.getTraceId() + "-" + exchange.getSpanId() + "-"
                    + exchange.getSpanContext().getTraceFlags().asHex(), PDP.traceparents().get(0));
        });
    }

    @Test
    void enforcesWithoutMicrometerObservationOnTheClassPath() {
        runner.withClassLoader(new FilteredClassLoader("io.micrometer.observation"))
                .run(context -> {
                    WebTestClient.bindToApplicationContext(context).build()
                            .get().uri("/things/1").exchange().expectStatus().isOk();

                    SpanData request = await("request");
                    SpanData call = await("authzen evaluation");
                    assertNotEquals(request.getTraceId(), call.getTraceId());
                });
    }

    private SpanData await(String name) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            List<SpanData> finished = spans.getFinishedSpanItems();
            Optional<SpanData> span = finished.stream().filter(s -> s.getName().equals(name)).findFirst();
            if (span.isPresent()) {
                return span.get();
            }
            assertTrue(System.nanoTime() < deadline, "No span " + name + " in " + finished);
            Thread.sleep(10);
        }
    }

    /**
     * Observes every request the way Spring WebFlux does: the observation of the request goes into
     * the Reactor Context, not onto a thread.
     */
    @Configuration(proxyBeanMethods = false)
    static class ObservedRequests {

        @Bean
        ObservationRegistry observationRegistry(OpenTelemetry openTelemetry) {
            OtelTracer tracer = new OtelTracer(openTelemetry.getTracer("test"), new OtelCurrentTraceContext(),
                    event -> { });
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));
            return registry;
        }

        @Bean
        WebFilter observingFilter(ObservationRegistry registry) {
            return (exchange, chain) -> {
                Observation observation = Observation.start("request", registry);
                return chain.filter(exchange)
                        .doFinally(signal -> observation.stop())
                        .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
            };
        }
    }
}
