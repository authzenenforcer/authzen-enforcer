package io.github.authzenenforcer.micrometer;

import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.event.ListenerDrops;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.List;
import java.util.Objects;

/**
 * Binds the meters that an {@link AuthZenClient} offers outside of events: how many events each
 * listener did not get because it could not keep up.
 *
 * <pre>{@code
 * AuthZenClientMetrics.bind(client, registry);
 * }</pre>
 *
 * <p>Meter: {@value #LISTENER_DROPPED}, a function counter with the tags {@code listener} (the
 * {@linkplain ListenerDrops.Listener#getName() name} of the listener) and {@code event} (the
 * lower-case {@link ListenerDrops.EventType}). A counter that grows means that a listener, such as
 * an audit trail, is missing events.
 *
 * <p>The registry reads the counts from the client when it publishes, and holds the client only
 * weakly. As a {@link MeterBinder}, an instance can be handed to frameworks that bind meters
 * themselves, such as Spring Boot.
 */
public final class AuthZenClientMetrics implements MeterBinder {

    /** The counter of events listeners did not get. */
    public static final String LISTENER_DROPPED = "authzen.client.listener.dropped";

    private final AuthZenClient client;
    private final Tags tags;

    /**
     * Creates a binder.
     *
     * @param client the client whose counts are exposed
     * @param tags   tags added to every meter, for example to tell clients apart
     */
    public AuthZenClientMetrics(AuthZenClient client, Iterable<Tag> tags) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.tags = Tags.of(Objects.requireNonNull(tags, "tags must not be null"));
    }

    /**
     * Binds the meters of a client.
     *
     * @param client   the client
     * @param registry the registry
     */
    public static void bind(AuthZenClient client, MeterRegistry registry) {
        bind(client, registry, Tags.empty());
    }

    /**
     * Binds the meters of a client with extra tags.
     *
     * @param client   the client
     * @param registry the registry
     * @param tags     tags added to every meter
     */
    public static void bind(AuthZenClient client, MeterRegistry registry, Iterable<Tag> tags) {
        new AuthZenClientMetrics(client, tags).bindTo(registry);
    }

    /**
     * Registers a counter for every listener of the client and every event type. The listeners of
     * a client are fixed when it is built.
     *
     * @param registry the registry
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        List<ListenerDrops.Listener> listeners = client.listenerDrops().getListeners();
        for (int i = 0; i < listeners.size(); i++) {
            int index = i;
            for (ListenerDrops.EventType type : ListenerDrops.EventType.values()) {
                FunctionCounter.builder(LISTENER_DROPPED, client,
                                c -> c.listenerDrops().getListeners().get(index).getDropped(type))
                        .description("Events a listener did not get because its queue was full")
                        .tags(tags)
                        .tag("listener", listeners.get(i).getName())
                        .tag("event", MicrometerPepListener.lower(type))
                        .register(registry);
            }
        }
    }
}
