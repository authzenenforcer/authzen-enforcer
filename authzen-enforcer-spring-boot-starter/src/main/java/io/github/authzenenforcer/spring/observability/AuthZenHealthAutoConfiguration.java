package io.github.authzenenforcer.spring.observability;

import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.spring.AuthZenAutoConfiguration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration of the {@link AuthZenHealthIndicator}, named {@code authzen}, when
 * {@code management.health.authzen.enabled=true} and Spring Boot's health support is present.
 *
 * <p>It is opt-in: the PDP is a dependency that every instance shares, so its outage should not
 * take instances out of service. It is not part of the liveness and readiness groups.
 */
@AutoConfiguration(after = AuthZenAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "management.health.authzen", name = "enabled", havingValue = "true")
@ConditionalOnBean(AuthZenClient.class)
public class AuthZenHealthAutoConfiguration {

    /**
     * Creates the auto-configuration.
     */
    public AuthZenHealthAutoConfiguration() {
        // Instantiated by Spring
    }

    /**
     * Creates the health indicator; its bean name gives it the name {@code authzen}.
     *
     * @param client the client
     * @return the indicator
     */
    @Bean
    @ConditionalOnMissingBean(name = "authzenHealthIndicator")
    public AuthZenHealthIndicator authzenHealthIndicator(AuthZenClient client) {
        return new AuthZenHealthIndicator(client);
    }
}
