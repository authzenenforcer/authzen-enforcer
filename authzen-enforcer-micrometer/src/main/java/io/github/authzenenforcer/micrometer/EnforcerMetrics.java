package io.github.authzenenforcer.micrometer;

import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.core.event.ListenerDrops;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.EnumSet;
import java.util.Objects;

/**
 * Binds the meters that an {@link Enforcer} offers outside of events: how many events each of its
 * listeners did not get because it could not keep up.
 *
 * <pre>{@code
 * EnforcerMetrics.bind(enforcer, registry);
 * }</pre>
 *
 * <p>Meter: {@value #LISTENER_DROPPED}, a function counter with the tags {@code listener} (the
 * {@linkplain ListenerDrops.Listener#getName() name} of the listener) and {@code event}
 * ({@code enforcement_completed}, the only event an enforcer publishes). The listeners of an
 * enforcer are its own, apart from those of its client, so a listener given to both is counted
 * once by {@link AuthZenClientMetrics} and once here. A counter that grows means that a listener,
 * such as an audit trail, is missing decisions.
 *
 * <p>The registry reads the counts from the enforcer when it publishes, and holds the enforcer only
 * weakly. As a {@link MeterBinder}, an instance can be handed to frameworks that bind meters
 * themselves, such as Spring Boot.
 */
public final class EnforcerMetrics implements MeterBinder {

    /** The counter of events listeners did not get. */
    public static final String LISTENER_DROPPED = "authzen.enforcer.listener.dropped";

    private final Enforcer enforcer;
    private final Tags tags;

    /**
     * Creates a binder.
     *
     * @param enforcer the enforcer whose counts are exposed
     * @param tags     tags added to every meter, for example to tell enforcers apart
     */
    public EnforcerMetrics(Enforcer enforcer, Iterable<Tag> tags) {
        this.enforcer = Objects.requireNonNull(enforcer, "enforcer must not be null");
        this.tags = Tags.of(Objects.requireNonNull(tags, "tags must not be null"));
    }

    /**
     * Binds the meters of an enforcer.
     *
     * @param enforcer the enforcer
     * @param registry the registry
     */
    public static void bind(Enforcer enforcer, MeterRegistry registry) {
        bind(enforcer, registry, Tags.empty());
    }

    /**
     * Binds the meters of an enforcer with extra tags.
     *
     * @param enforcer the enforcer
     * @param registry the registry
     * @param tags     tags added to every meter
     */
    public static void bind(Enforcer enforcer, MeterRegistry registry, Iterable<Tag> tags) {
        new EnforcerMetrics(enforcer, tags).bindTo(registry);
    }

    /**
     * Registers a counter for every listener of the enforcer. The listeners of an enforcer are
     * fixed when it is built.
     *
     * @param registry the registry
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        ListenerDropsMeters.register(registry, LISTENER_DROPPED, enforcer, Enforcer::listenerDrops,
                EnumSet.of(ListenerDrops.EventType.ENFORCEMENT_COMPLETED), tags);
    }
}
