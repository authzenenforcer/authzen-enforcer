package io.github.authzenenforcer.micrometer;

import io.github.authzenenforcer.core.event.AttemptCompleted;
import io.github.authzenenforcer.core.event.BreakerTransition;
import io.github.authzenenforcer.core.event.CallCompleted;
import io.github.authzenenforcer.core.event.MetadataRefresh;
import io.github.authzenenforcer.core.event.Operation;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.spi.PepListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.noop.NoopGauge;

import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link PepListener} that records the events of an
 * {@link io.github.authzenenforcer.core.client.AuthZenClient} as Micrometer meters.
 *
 * <pre>{@code
 * MicrometerPepListener metrics = MicrometerPepListener.builder(registry)
 *         .tags(Tags.of("pdp", "main"))
 *         .build();
 * AuthZenClient client = AuthZenClient.builder()
 *         .pdpUrl(URI.create("https://pdp.example.com"))
 *         .listener(metrics)
 *         .build();
 * AuthZenClientMetrics.bind(client, registry, Tags.of("pdp", "main"));
 * }</pre>
 *
 * <p>Meters:
 * <table>
 *   <caption>Meters and their tags</caption>
 *   <tr><th>Name</th><th>Type</th><th>Tags</th></tr>
 *   <tr><td>{@value #CALLS}</td><td>timer</td>
 *       <td>{@code operation}, {@code outcome}, {@code exception}; opt-in {@code resource.type},
 *       {@code action.name}</td></tr>
 *   <tr><td>{@value #ATTEMPTS}</td><td>timer</td>
 *       <td>{@code operation}, {@code status}, {@code exception}, {@code next}</td></tr>
 *   <tr><td>{@value #BREAKER_TRANSITIONS}</td><td>counter</td>
 *       <td>{@code breaker}, {@code from}, {@code to}</td></tr>
 *   <tr><td>{@value #BREAKER_STATE}</td><td>gauge</td>
 *       <td>{@code breaker}; 0 closed, 1 half-open, 2 open</td></tr>
 *   <tr><td>{@value #METADATA_REFRESHES}</td><td>counter</td><td>{@code result}</td></tr>
 * </table>
 *
 * <p>Tag values are lower case. {@code exception} is the simple class name of the failure, or
 * {@code none}; {@code status} is the HTTP status, {@code invalid} for a status outside 100–599,
 * or {@code none} if no response arrived. No tag
 * carries an identifier, such as a subject id, resource id or request id, so the number of time
 * series stays bounded. Timers publish no histogram unless a {@code MeterFilter} configures one.
 *
 * <p>The breaker gauges start at closed, as the breakers of a new client do, so one listener
 * instance belongs to one client; clients that share a registry need distinct {@link
 * Builder#tags(Iterable) tags}, and {@link Builder#build()} rejects a second listener whose
 * gauges would have the same tags as those of an earlier one, unless those gauges were removed from
 * the registry first, as when a client is rebuilt. Their listeners must use the same tag keys and the same opt-in
 * tags, though: some registries, such as Prometheus, reject a meter whose tag keys differ from
 * those of an earlier meter with the same name, and the rejected metric is then missing. For
 * example, give every client a {@code pdp} tag rather than only some of them. Listener drops are
 * not events; {@link AuthZenClientMetrics} exposes them.
 *
 * <p>Instances are thread-safe.
 */
public final class MicrometerPepListener implements PepListener {

    /** The timer of logical calls, with their retries. */
    public static final String CALLS = "authzen.client.calls";

    /** The timer of HTTP exchanges with the PDP. */
    public static final String ATTEMPTS = "authzen.client.attempts";

    /** The counter of circuit breaker transitions. */
    public static final String BREAKER_TRANSITIONS = "authzen.client.breaker.transitions";

    /** The gauge of the current circuit breaker states. */
    public static final String BREAKER_STATE = "authzen.client.breaker.state";

    /** The counter of metadata refreshes. */
    public static final String METADATA_REFRESHES = "authzen.client.metadata.refreshes";

    static final String NONE = "none";

    static final String INVALID = "invalid";

    // The breaker gauges listeners registered, per registry, so that a second listener whose gauges
    // would silently show the first one's states is rejected. A gauge removed from the registry is
    // replaced by a new instance on the next registration, so the identity check lets that through
    private static final Map<MeterRegistry, Map<Meter.Id, WeakReference<Gauge>>> GAUGES = new WeakHashMap<>();
    private static final ReentrantLock GAUGES_LOCK = new ReentrantLock();

    private final MeterRegistry registry;
    private final Tags tags;
    private final boolean resourceTypeTag;
    private final boolean actionNameTag;
    private final Map<BreakerTransition.Breaker, AtomicInteger> breakerStates =
            new EnumMap<>(BreakerTransition.Breaker.class);

    private MicrometerPepListener(Builder builder) {
        this.registry = builder.registry;
        this.tags = builder.tags;
        this.resourceTypeTag = builder.resourceTypeTag;
        this.actionNameTag = builder.actionNameTag;
        for (BreakerTransition.Breaker breaker : BreakerTransition.Breaker.values()) {
            AtomicInteger state = new AtomicInteger(stateValue(BreakerTransition.State.CLOSED));
            breakerStates.put(breaker, state);
            Gauge gauge = Gauge.builder(BREAKER_STATE, state, AtomicInteger::get)
                    .description("The state of a circuit breaker: 0 closed, 1 half-open, 2 open")
                    .tags(tags)
                    .tag("breaker", lower(breaker))
                    .strongReference(true)
                    .register(registry);
            claim(registry, gauge);
        }
    }

    // A registry returns the existing gauge for an id it already has, which reads another state
    private static void claim(MeterRegistry registry, Gauge gauge) {
        if (gauge instanceof NoopGauge) {
            // Denied by a MeterFilter, so nothing is published that could be wrong
            return;
        }
        boolean taken;
        GAUGES_LOCK.lock();
        try {
            Map<Meter.Id, WeakReference<Gauge>> claimed = GAUGES.computeIfAbsent(registry, r -> new HashMap<>());
            WeakReference<Gauge> previous = claimed.get(gauge.getId());
            taken = previous != null && previous.get() == gauge;
            if (!taken) {
                claimed.put(gauge.getId(), new WeakReference<>(gauge));
            }
        } finally {
            GAUGES_LOCK.unlock();
        }
        if (taken) {
            throw new IllegalStateException("The registry already has the breaker gauges of another listener with"
                    + " the tags " + gauge.getId().getTags() + "; give each client's listener distinct tags");
        }
    }

    /**
     * Returns a new builder.
     *
     * @param registry the registry the meters are registered in
     * @return the builder
     */
    public static Builder builder(MeterRegistry registry) {
        return new Builder(Objects.requireNonNull(registry, "registry must not be null"));
    }

    @Override
    public void onCallCompleted(CallCompleted<?, ?> event) {
        Tags callTags = tags.and(
                Tag.of("operation", event.getOperation().getName()),
                Tag.of("outcome", lower(event.getOutcome())),
                Tag.of("exception", exception(event.getFailure())));
        if (resourceTypeTag || actionNameTag) {
            // Tag keys must be the same for every operation; only single evaluations name one resource
            Optional<EvaluationRequest> evaluation = event.as(Operation.EVALUATION).map(CallCompleted::getRequest);
            if (resourceTypeTag) {
                callTags = callTags.and("resource.type",
                        evaluation.map(request -> request.getResource().getType()).orElse(NONE));
            }
            if (actionNameTag) {
                callTags = callTags.and("action.name",
                        evaluation.map(request -> request.getAction().getName()).orElse(NONE));
            }
        }
        Timer.builder(CALLS)
                .description("Calls to the PDP, including their retries")
                .tags(callTags)
                .register(registry)
                .record(event.getDuration());
    }

    @Override
    public void onAttemptCompleted(AttemptCompleted event) {
        Timer.builder(ATTEMPTS)
                .description("HTTP exchanges with the PDP")
                .tags(tags)
                .tag("operation", event.getOperation().getName())
                .tag("status", status(event.getStatus()))
                .tag("exception", exception(event.getFailure()))
                .tag("next", lower(event.getNext()))
                .register(registry)
                .record(event.getDuration());
    }

    @Override
    public void onBreakerTransition(BreakerTransition event) {
        breakerStates.get(event.getBreaker()).set(stateValue(event.getTo()));
        Counter.builder(BREAKER_TRANSITIONS)
                .description("Circuit breaker transitions")
                .tags(tags)
                .tag("breaker", lower(event.getBreaker()))
                .tag("from", lower(event.getFrom()))
                .tag("to", lower(event.getTo()))
                .register(registry)
                .increment();
    }

    @Override
    public void onMetadataRefresh(MetadataRefresh event) {
        Counter.builder(METADATA_REFRESHES)
                .description("Attempts to refresh the PDP metadata")
                .tags(tags)
                .tag("result", lower(event.getResult()))
                .register(registry)
                .increment();
    }

    private static int stateValue(BreakerTransition.State state) {
        switch (state) {
            case CLOSED:
                return 0;
            case HALF_OPEN:
                return 1;
            default:
                return 2;
        }
    }

    // HTTP/2 lets a PDP send any integer as its status, which must not become a new series each time
    static String status(OptionalInt status) {
        if (status.isEmpty()) {
            return NONE;
        }
        int code = status.getAsInt();
        return code >= 100 && code <= 599 ? String.valueOf(code) : INVALID;
    }

    private static String exception(Optional<? extends Throwable> failure) {
        return failure.map(f -> {
            String name = f.getClass().getSimpleName();
            // Anonymous classes have no simple name
            return name.isEmpty() ? f.getClass().getName() : name;
        }).orElse(NONE);
    }

    static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** Builds a {@link MicrometerPepListener}. */
    public static final class Builder {

        private final MeterRegistry registry;
        private Tags tags = Tags.empty();
        private boolean resourceTypeTag;
        private boolean actionNameTag;

        private Builder(MeterRegistry registry) {
            this.registry = registry;
        }

        /**
         * Adds tags to every meter, for example to tell clients apart.
         *
         * @param tags the tags
         * @return this builder
         */
        public Builder tags(Iterable<Tag> tags) {
            this.tags = this.tags.and(Objects.requireNonNull(tags, "tags must not be null"));
            return this;
        }

        /**
         * Adds a tag to every meter.
         *
         * @param key   the key
         * @param value the value
         * @return this builder
         */
        public Builder tag(String key, String value) {
            this.tags = this.tags.and(Objects.requireNonNull(key, "key must not be null"),
                    Objects.requireNonNull(value, "value must not be null"));
            return this;
        }

        /**
         * Tags {@value MicrometerPepListener#CALLS} with the resource type of a single evaluation, {@code none} for other
         * operations. Off by default: every resource type adds time series, so enable it only if the
         * types are few and fixed.
         *
         * @param enabled whether to add the tag
         * @return this builder
         */
        public Builder resourceTypeTag(boolean enabled) {
            this.resourceTypeTag = enabled;
            return this;
        }

        /**
         * Tags {@value MicrometerPepListener#CALLS} with the action name of a single evaluation, {@code none} for other
         * operations. Off by default: every action name adds time series, so enable it only if the
         * names are few and fixed.
         *
         * @param enabled whether to add the tag
         * @return this builder
         */
        public Builder actionNameTag(boolean enabled) {
            this.actionNameTag = enabled;
            return this;
        }

        /**
         * Builds the listener and registers its breaker gauges.
         *
         * @return the listener
         * @throws IllegalStateException if the registry already has breaker gauges with the same
         *                               tags from another listener
         */
        public MicrometerPepListener build() {
            return new MicrometerPepListener(this);
        }
    }
}
