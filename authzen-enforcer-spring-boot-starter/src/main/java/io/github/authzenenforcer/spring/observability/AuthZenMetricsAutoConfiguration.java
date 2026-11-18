package io.github.authzenenforcer.spring.observability;

import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.micrometer.AuthZenClientMetrics;
import io.github.authzenenforcer.micrometer.EnforcerMetrics;
import io.github.authzenenforcer.micrometer.MicrometerPepListener;
import io.github.authzenenforcer.spring.AuthZenAutoConfiguration;
import io.github.authzenenforcer.spring.AuthZenProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration of the Micrometer metrics of the client and the enforcer, when
 * authzen-enforcer-micrometer is present and the application has a {@link MeterRegistry} bean.
 *
 * <p>The {@link MicrometerPepListener} is a listener bean, so that the auto-configured client and
 * enforcer report to it; a client or enforcer of the application must register it itself. Common
 * tags, such as one that tells several applications apart, come from
 * {@code management.metrics.tags.*}. Turned off with {@code authzen.metrics.enabled=false}; each
 * bean is created only if the application defines none of its type.
 */
@AutoConfiguration(after = AuthZenAutoConfiguration.class,
        afterName = "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration")
@ConditionalOnClass({MeterRegistry.class, MicrometerPepListener.class})
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "authzen.metrics", name = "enabled", matchIfMissing = true)
public class AuthZenMetricsAutoConfiguration {

    /**
     * Creates the auto-configuration.
     */
    public AuthZenMetricsAutoConfiguration() {
        // Instantiated by Spring
    }

    /**
     * Creates the listener that records calls, exchanges, breaker transitions, metadata refreshes
     * and enforcements.
     *
     * @param registry   the registry
     * @param properties the properties
     * @return the listener
     */
    @Bean
    @ConditionalOnMissingBean
    public MicrometerPepListener authZenMicrometerPepListener(MeterRegistry registry, AuthZenProperties properties) {
        AuthZenProperties.Metrics metrics = properties.getMetrics();
        return MicrometerPepListener.builder(registry)
                .resourceTypeTag(metrics.isResourceTypeTag())
                .actionNameTag(metrics.isActionNameTag())
                .build();
    }

    /**
     * Creates the binder of the client's listener drops.
     *
     * @param client the client
     * @return the binder
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AuthZenClient.class)
    public AuthZenClientMetrics authZenClientMetrics(AuthZenClient client) {
        return new AuthZenClientMetrics(client, Tags.empty());
    }

    /**
     * Creates the binder of the enforcer's listener drops.
     *
     * @param enforcer the enforcer
     * @return the binder
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Enforcer.class)
    public EnforcerMetrics authZenEnforcerMetrics(Enforcer enforcer) {
        return new EnforcerMetrics(enforcer, Tags.empty());
    }
}
