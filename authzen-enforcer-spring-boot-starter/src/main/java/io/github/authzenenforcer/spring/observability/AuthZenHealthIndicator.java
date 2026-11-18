package io.github.authzenenforcer.spring.observability;

import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.client.ClientStatus;
import io.github.authzenenforcer.core.event.BreakerTransition;
import io.github.authzenenforcer.core.event.MetadataRefresh;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reports the state of an {@link AuthZenClient} as {@link AuthZenClient#status()} sees it, without
 * contacting the PDP.
 *
 * <p>It is {@code DOWN} while calls deny at once: while the breaker of the authorization API is
 * open, or after the last metadata refresh failed. A half-open breaker and stale metadata are
 * {@code UP}, since calls go on. A client that does not track its state is {@code UNKNOWN}.
 *
 * <p>The details name the states of the breakers, and the result and time of the last metadata
 * refresh with the class of its failure; never a failure message, which may come from the PDP or
 * from integrator code.
 */
public final class AuthZenHealthIndicator implements HealthIndicator {

    private final AuthZenClient client;

    /**
     * Creates an indicator.
     *
     * @param client the client
     */
    public AuthZenHealthIndicator(AuthZenClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public Health health() {
        Optional<ClientStatus> current = client.status();
        if (current.isEmpty()) {
            return Health.unknown().build();
        }
        ClientStatus status = current.get();
        Optional<MetadataRefresh> refresh = status.getLastMetadataRefresh();
        boolean down = status.getAuthorizationApiBreaker() == BreakerTransition.State.OPEN
                || refresh.map(MetadataRefresh::getResult).orElse(null) == MetadataRefresh.Result.FAILED;
        Health.Builder health = down ? Health.down() : Health.up();
        Map<String, Object> breakers = new LinkedHashMap<>();
        breakers.put("authorizationApi", name(status.getAuthorizationApiBreaker()));
        status.getMetadataDiscoveryBreaker().ifPresent(state -> breakers.put("metadataDiscovery", name(state)));
        health.withDetail("breakers", breakers);
        refresh.ifPresent(last -> health.withDetail("metadata", details(last)));
        return health.build();
    }

    private static Map<String, Object> details(MetadataRefresh refresh) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("result", name(refresh.getResult()));
        details.put("time", refresh.getTime().toString());
        refresh.getFailure().ifPresent(failure -> details.put("failure", failure.getClass().getName()));
        return details;
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
