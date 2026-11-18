package io.github.authzenenforcer.spring.observability;

import io.github.authzenenforcer.core.spi.PepTracer;
import io.github.authzenenforcer.opentelemetry.OpenTelemetryTracing;
import io.github.authzenenforcer.spring.AuthZenProperties;

import io.opentelemetry.api.OpenTelemetry;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration of the OpenTelemetry traces of the client, when
 * authzen-enforcer-opentelemetry is present and the application has an {@link OpenTelemetry} bean,
 * such as the one of spring-boot-starter-opentelemetry.
 *
 * <p>The tracer is a {@link PepTracer} bean, which the auto-configured client uses; a client of the
 * application must set it itself. Turned off with {@code authzen.tracing.enabled=false}; a
 * {@link PepTracer} bean of the application replaces it.
 */
@AutoConfiguration(afterName = "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration")
@ConditionalOnClass({OpenTelemetry.class, OpenTelemetryTracing.class})
@ConditionalOnBean(OpenTelemetry.class)
@ConditionalOnProperty(prefix = "authzen.tracing", name = "enabled", matchIfMissing = true)
public class AuthZenTracingAutoConfiguration {

    /**
     * Creates the auto-configuration.
     */
    public AuthZenTracingAutoConfiguration() {
        // Instantiated by Spring
    }

    /**
     * Creates the tracer.
     *
     * @param openTelemetry the OpenTelemetry instance
     * @param properties    the properties
     * @return the tracer
     */
    @Bean
    @ConditionalOnMissingBean
    public PepTracer authZenTracer(OpenTelemetry openTelemetry, AuthZenProperties properties) {
        AuthZenProperties.Tracing tracing = properties.getTracing();
        return OpenTelemetryTracing.builder(openTelemetry)
                .resourceTypeAttribute(tracing.isResourceTypeAttribute())
                .actionNameAttribute(tracing.isActionNameAttribute())
                .build();
    }
}
