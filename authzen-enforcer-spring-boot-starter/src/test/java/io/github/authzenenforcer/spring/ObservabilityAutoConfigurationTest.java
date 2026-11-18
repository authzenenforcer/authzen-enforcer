package io.github.authzenenforcer.spring;

import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.client.CircuitBreakerOptions;
import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.core.event.ListenerDrops;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.spi.PepListener;
import io.github.authzenenforcer.core.spi.PepTracer;
import io.github.authzenenforcer.micrometer.AuthZenClientMetrics;
import io.github.authzenenforcer.micrometer.EnforcerMetrics;
import io.github.authzenenforcer.micrometer.MicrometerPepListener;
import io.github.authzenenforcer.opentelemetry.OpenTelemetryTracing;
import io.github.authzenenforcer.spring.observability.AuthZenHealthAutoConfiguration;
import io.github.authzenenforcer.spring.observability.AuthZenHealthIndicator;
import io.github.authzenenforcer.spring.observability.AuthZenMetricsAutoConfiguration;
import io.github.authzenenforcer.spring.observability.AuthZenTracingAutoConfiguration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilityAutoConfigurationTest {

    private static final EvaluationRequest REQUEST = EvaluationRequest.of(
            Subject.of("user", "alice"), Action.of("read"), Resource.of("documents", "42"));

    private final FakePdp pdp = new FakePdp();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthZenAutoConfiguration.class,
                    AuthZenMetricsAutoConfiguration.class, AuthZenTracingAutoConfiguration.class,
                    AuthZenHealthAutoConfiguration.class))
            .withPropertyValues("authzen.pdp.url=" + pdp.url(), "authzen.pdp.max-retries=0");

    private final ApplicationContextRunner metrics = runner.withConfiguration(AutoConfigurations.of(
            MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class,
            SimpleMetricsExportAutoConfiguration.class));

    @AfterEach
    void stopPdp() {
        pdp.close();
    }

    private static List<Class<?>> listenerTypes(ListenerDrops drops) {
        return drops.getListeners().stream().<Class<?>>map(listener -> listener.getListener().getClass()).toList();
    }

    private static <T> T last(List<T> values) {
        return values.get(values.size() - 1);
    }

    // Events reach the listeners after the caller has its result
    private static void await(Runnable assertion) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() - deadline > 0) {
                    throw e;
                }
                Thread.sleep(10);
            }
        }
    }

    @Test
    void recordsMetricsOfTheClientAndTheEnforcer() {
        metrics.run(context -> {
            MicrometerPepListener listener = context.getBean(MicrometerPepListener.class);
            assertTrue(listenerTypes(context.getBean(AuthZenClient.class).listenerDrops())
                    .contains(MicrometerPepListener.class));
            assertTrue(listenerTypes(context.getBean(Enforcer.class).listenerDrops())
                    .contains(MicrometerPepListener.class));
            assertNotNull(listener);
            assertNotNull(context.getBean(AuthZenClientMetrics.class));
            assertNotNull(context.getBean(EnforcerMetrics.class));

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            context.getBean(AuthZenClient.class).evaluate(REQUEST);

            await(() -> assertNotNull(registry.find(MicrometerPepListener.CALLS).tag("outcome", "permit").timer()));
            assertNotNull(registry.find(MicrometerPepListener.BREAKER_STATE).gauge());
            assertNotNull(registry.find(AuthZenClientMetrics.LISTENER_DROPPED).functionCounter());
            assertNotNull(registry.find(EnforcerMetrics.LISTENER_DROPPED).functionCounter());
        });
    }

    @Test
    void tagsCallsWithTheResourceTypeAndTheActionNameOnRequest() {
        metrics.withPropertyValues("authzen.metrics.resource-type-tag=true", "authzen.metrics.action-name-tag=true")
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    context.getBean(AuthZenClient.class).evaluate(REQUEST);

                    await(() -> {
                        Timer calls = registry.find(MicrometerPepListener.CALLS).timer();
                        assertNotNull(calls);
                        assertEquals("documents", calls.getId().getTag("resource.type"));
                        assertEquals("read", calls.getId().getTag("action.name"));
                    });
                });
    }

    @Test
    void recordsNoMetricsWhenTurnedOff() {
        metrics.withPropertyValues("authzen.metrics.enabled=false").run(context -> {
            assertFalse(context.containsBean("authZenMicrometerPepListener"));
            assertTrue(context.getBeansOfType(AuthZenClientMetrics.class).isEmpty());
            assertTrue(listenerTypes(context.getBean(AuthZenClient.class).listenerDrops()).isEmpty());
        });
    }

    @Test
    void recordsNoMetricsWithoutARegistry() {
        runner.run(context -> {
            assertTrue(context.getBeansOfType(MicrometerPepListener.class).isEmpty());
            assertTrue(context.getBeansOfType(AuthZenClientMetrics.class).isEmpty());
        });
    }

    @Test
    void letsTheApplicationsListenerReplaceTheDefault() {
        metrics.withBean("custom", MicrometerPepListener.class,
                        () -> MicrometerPepListener.builder(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
                                .build())
                .run(context -> assertSame(context.getBean("custom"), context.getBean(MicrometerPepListener.class)));
    }

    @Test
    void tracesCallsAndSendsTheirTraceContextToThePdp() {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
                .build();
        runner.withBean(OpenTelemetry.class, () -> openTelemetry).run(context -> {
            assertTrue(context.getBean(PepTracer.class) instanceof OpenTelemetryTracing);
            AuthZenClient client = context.getBean(AuthZenClient.class);
            assertEquals(OpenTelemetryTracing.class, last(listenerTypes(client.listenerDrops())));

            client.evaluate(REQUEST);

            await(() -> assertTrue(spans.getFinishedSpanItems().stream().map(SpanData::getName)
                    .anyMatch("authzen evaluation"::equals)));
            SpanData call = spans.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("authzen evaluation")).findFirst().orElseThrow();
            assertTrue(pdp.traceparents().get(0).contains(call.getTraceId()));
            assertEquals("documents", call.getAttributes().asMap().entrySet().stream()
                    .filter(attribute -> attribute.getKey().getKey().equals("authzen.resource.type"))
                    .map(attribute -> attribute.getValue().toString()).findFirst().orElseThrow());
        });
        openTelemetry.close();
    }

    @Test
    void leavesOutTheResourceTypeAndTheActionNameOnRequest() {
        InMemorySpanExporter spans = InMemorySpanExporter.create();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
                .build();
        runner.withBean(OpenTelemetry.class, () -> openTelemetry)
                .withPropertyValues("authzen.tracing.resource-type-attribute=false",
                        "authzen.tracing.action-name-attribute=false")
                .run(context -> {
                    context.getBean(AuthZenClient.class).evaluate(REQUEST);

                    await(() -> assertFalse(spans.getFinishedSpanItems().isEmpty()));
                    assertTrue(spans.getFinishedSpanItems().stream()
                            .flatMap(span -> span.getAttributes().asMap().keySet().stream())
                            .noneMatch(key -> key.getKey().equals("authzen.resource.type")
                                    || key.getKey().equals("authzen.action.name")));
                });
        openTelemetry.close();
    }

    @Test
    void tracesNothingWhenTurnedOffOrWithoutOpenTelemetry() {
        runner.run(context -> assertTrue(context.getBeansOfType(PepTracer.class).isEmpty()));
        runner.withBean(OpenTelemetry.class, OpenTelemetry::noop)
                .withPropertyValues("authzen.tracing.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(PepTracer.class).isEmpty()));
    }

    @Test
    void usesTheApplicationsTracer() {
        PepTracer tracer = OpenTelemetryTracing.builder(OpenTelemetry.noop()).build();
        runner.withBean(OpenTelemetry.class, OpenTelemetry::noop)
                .withBean(PepTracer.class, () -> tracer)
                .run(context -> {
                    assertSame(tracer, context.getBean(PepTracer.class));
                    assertSame(tracer, last(context.getBean(AuthZenClient.class).listenerDrops().getListeners())
                            .getListener());
                });
    }

    @Test
    void failsToStartWithTwoTracers() {
        runner.withBean("first", PepTracer.class, () -> OpenTelemetryTracing.builder(OpenTelemetry.noop()).build())
                .withBean("second", PepTracer.class, () -> OpenTelemetryTracing.builder(OpenTelemetry.noop()).build())
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void addsNoHealthIndicatorUnlessEnabled() {
        runner.run(context -> assertTrue(context.getBeansOfType(AuthZenHealthIndicator.class).isEmpty()));
    }

    @Test
    void reportsTheClientAsUpWithoutContactingThePdp() {
        runner.withPropertyValues("management.health.authzen.enabled=true").run(context -> {
            Health health = context.getBean("authzenHealthIndicator", AuthZenHealthIndicator.class).health();

            assertEquals(Status.UP, health.getStatus());
            assertEquals(Map.of("authorizationApi", "closed", "metadataDiscovery", "closed"),
                    health.getDetails().get("breakers"));
            assertFalse(health.getDetails().containsKey("metadata"));
            assertTrue(pdp.requests().isEmpty());

            context.getBean(AuthZenClient.class).evaluate(REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) context.getBean(AuthZenHealthIndicator.class)
                    .health().getDetails().get("metadata");
            assertEquals("default_paths", metadata.get("result"));
            assertNotNull(metadata.get("time"));
        });
    }

    @Test
    void reportsTheClientAsDownWhileItsBreakerIsOpen() {
        pdp.status(503);
        AuthZenClient client = AuthZenClient.builder().pdpUrl(pdp.url()).discovery(false).maxRetries(0)
                .circuitBreaker(CircuitBreakerOptions.builder().failureThreshold(1).build())
                .build();
        runner.withPropertyValues("management.health.authzen.enabled=true")
                .withBean(AuthZenClient.class, () -> client)
                .run(context -> {
                    assertThrows(RuntimeException.class, () -> client.evaluate(REQUEST));

                    Health health = context.getBean(AuthZenHealthIndicator.class).health();
                    assertEquals(Status.DOWN, health.getStatus());
                    assertEquals(Map.of("authorizationApi", "open"), health.getDetails().get("breakers"));
                });
    }

    @Test
    void reportsTheClientAsDownAfterAFailedRefreshWithoutTheFailureMessage() {
        // A PDP whose port is closed
        FakePdp closed = new FakePdp();
        String url = closed.url();
        closed.close();
        AuthZenClient client = AuthZenClient.builder().pdpUrl(url).maxRetries(0).build();
        runner.withPropertyValues("management.health.authzen.enabled=true")
                .withBean(AuthZenClient.class, () -> client)
                .run(context -> {
                    assertThrows(RuntimeException.class, () -> client.evaluate(REQUEST));

                    Health health = context.getBean(AuthZenHealthIndicator.class).health();
                    assertEquals(Status.DOWN, health.getStatus());
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = (Map<String, Object>) health.getDetails().get("metadata");
                    assertEquals("failed", metadata.get("result"));
                    assertEquals("io.github.authzenenforcer.core.PdpCommunicationException", metadata.get("failure"));
                    assertFalse(health.toString().contains(url));
                });
    }

    @Test
    void reportsAClientThatDoesNotTrackItsStateAsUnknown() {
        AuthZenClient client = (AuthZenClient) Proxy.newProxyInstance(AuthZenClient.class.getClassLoader(),
                new Class<?>[] {AuthZenClient.class}, InvocationHandler::invokeDefault);

        assertEquals(Status.UNKNOWN, new AuthZenHealthIndicator(client).health().getStatus());
    }

    @Test
    void registersTheTracerAfterTheApplicationsListeners() {
        PepListener listener = new PepListener() {
        };
        runner.withBean(OpenTelemetry.class, OpenTelemetry::noop)
                .withBean(PepListener.class, () -> listener)
                .run(context -> {
                    List<ListenerDrops.Listener> listeners =
                            context.getBean(AuthZenClient.class).listenerDrops().getListeners();
                    assertEquals(2, listeners.size());
                    assertSame(listener, listeners.get(0).getListener());
                    assertSame(context.getBean(PepTracer.class), listeners.get(1).getListener());
                });
    }
}
