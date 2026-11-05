package io.github.authzenenforcer.core.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Which values of requests and responses a {@link io.github.authzenenforcer.core.spi.PepListener}
 * sees in {@link CallCompleted} events, and which it sees masked.
 *
 * <p>A masked value is replaced by {@link #MASK}; its name stays visible, as in the
 * {@code toString()} of the model types. {@link #defaults()} masks the personal data that
 * {@code toString()} masks: the subject id and the values of all properties and of the context.
 * Paths name the values:
 * <ul>
 *   <li>{@code subject.id}, {@code resource.id}</li>
 *   <li>{@code subject.properties.<name>}, {@code resource.properties.<name>},
 *       {@code action.properties.<name>}</li>
 *   <li>{@code context.<name>}, for the context of requests and of responses</li>
 * </ul>
 * A {@code *} in place of the name stands for every name of that group; a rule for a single name
 * takes precedence over it, whatever the order of the calls. A path applies wherever the value
 * occurs: in the request, in the items and defaults of a batch, and in search results. The subject
 * properties of a Subject Search and the resource properties of a Resource Search count as
 * {@code subject.properties} and {@code resource.properties}.
 *
 * <pre>{@code
 * EventContent content = EventContent.defaults()
 *         .include("subject.properties.tenant")   // visible on purpose
 *         .exclude("resource.id");                // stricter than the default
 * }</pre>
 *
 * <p>Types, action names, decisions, the options of a batch evaluation, and page details (tokens,
 * limits, counts and page properties) are always visible, although {@code toString()} masks the
 * option values, page tokens and page properties. Keep personal data out of them. Credentials,
 * tokens and DPoP proofs are never part of an event, whatever the content says.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class EventContent {

    /** The value that replaces a masked value. */
    public static final String MASK = "***";

    private static final String WILDCARD = "*";
    private static final List<String> IDS = List.of("subject.id", "resource.id");
    private static final List<String> GROUPS = List.of("subject.properties.", "resource.properties.",
            "action.properties.", "context.");

    private static final EventContent DEFAULTS = new EventContent(Map.of(
            "subject.id", true,
            "subject.properties.*", true,
            "resource.properties.*", true,
            "action.properties.*", true,
            "context.*", true));

    // Path to whether it is masked
    private final Map<String, Boolean> rules;

    private EventContent(Map<String, Boolean> rules) {
        this.rules = Map.copyOf(rules);
    }

    /**
     * Returns the default content, which masks the personal data that the {@code toString()} of
     * the model types masks: {@code subject.id}, {@code subject.properties.*}, {@code resource.properties.*},
     * {@code action.properties.*} and {@code context.*}.
     *
     * @return the default content
     */
    public static EventContent defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy that shows the value at the path unmasked.
     *
     * @param path a path such as {@code subject.properties.tenant} or {@code context.*}
     * @return the copy
     * @throws IllegalArgumentException if the path names no maskable value
     */
    public EventContent include(String path) {
        return with(path, false);
    }

    /**
     * Returns a copy that masks the value at the path.
     *
     * @param path a path such as {@code resource.id} or {@code resource.properties.owner}
     * @return the copy
     * @throws IllegalArgumentException if the path names no maskable value
     */
    public EventContent exclude(String path) {
        return with(path, true);
    }

    private EventContent with(String path, boolean masked) {
        check(path);
        Map<String, Boolean> copy = new LinkedHashMap<>(rules);
        copy.put(path, masked);
        return new EventContent(copy);
    }

    /**
     * Tells whether the value at the path is masked.
     *
     * @param path a path such as {@code subject.id} or {@code context.reason}
     * @return {@code true} if listeners see {@link #MASK} in place of the value
     * @throws IllegalArgumentException if the path names no maskable value
     */
    public boolean isMasked(String path) {
        String group = check(path);
        Boolean exact = rules.get(path);
        if (exact != null) {
            return exact;
        }
        return group != null && rules.getOrDefault(group + WILDCARD, false);
    }

    // For names from requests and responses, which may be any string, even an empty one or one
    // that only a rule for another name could match; such a name follows the group's wildcard
    boolean isMasked(String group, String name) {
        Boolean exact = name.isEmpty() || name.equals(WILDCARD) ? null : rules.get(group + name);
        if (exact != null) {
            return exact;
        }
        return rules.getOrDefault(group + WILDCARD, false);
    }

    // Returns the group prefix of a property or context path, or null for an id
    private static String check(String path) {
        Objects.requireNonNull(path, "path must not be null");
        if (IDS.contains(path)) {
            return null;
        }
        for (String group : GROUPS) {
            if (path.startsWith(group) && path.length() > group.length()) {
                return group;
            }
        }
        throw new IllegalArgumentException("path names no maskable value: " + path);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EventContent other && rules.equals(other.rules);
    }

    @Override
    public int hashCode() {
        return rules.hashCode();
    }

    @Override
    public String toString() {
        return "EventContent" + new TreeMap<>(rules);
    }
}
