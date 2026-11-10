package io.github.authzenenforcer.core.enforce;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit values configured per route, for example from application properties. They win over
 * inference and lose to the explicit values of the endpoint itself.
 *
 * <p>A route is identified by its pattern exactly as the framework declares it, such as
 * {@code /documents/{id}}, and optionally by an HTTP method. Values for a method win over values for
 * every method of the same route.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class RouteOverrides {

    private static final RouteOverrides NONE = new Builder().build();
    // Stands for every method; no HTTP method is empty
    private static final String ANY_METHOD = "";

    private final Map<String, Map<String, PermissionSpec>> routes;

    private RouteOverrides(Builder builder) {
        Map<String, Map<String, PermissionSpec>> copy = new HashMap<>();
        builder.routes.forEach((pattern, methods) -> copy.put(pattern, Map.copyOf(methods)));
        this.routes = Map.copyOf(copy);
    }

    /**
     * Returns overrides without any route.
     *
     * @return the empty overrides
     */
    public static RouteOverrides none() {
        return NONE;
    }

    /**
     * Returns a new builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the values configured for a request.
     *
     * @param method       the HTTP method
     * @param routePattern the route pattern
     * @return the values for the method combined with those for every method, or an empty spec
     */
    public PermissionSpec forRoute(String method, String routePattern) {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(routePattern, "routePattern must not be null");
        Map<String, PermissionSpec> methods = routes.get(routePattern);
        if (methods == null) {
            return PermissionSpec.none();
        }
        PermissionSpec any = methods.getOrDefault(ANY_METHOD, PermissionSpec.none());
        return methods.getOrDefault(method.toUpperCase(Locale.ROOT), PermissionSpec.none()).orElse(any);
    }

    @Override
    public String toString() {
        return "RouteOverrides" + routes;
    }

    /**
     * Builder for {@link RouteOverrides}. Not thread-safe.
     */
    public static final class Builder {

        private final Map<String, Map<String, PermissionSpec>> routes = new HashMap<>();

        private Builder() {
        }

        /**
         * Sets the values for every method of a route, replacing earlier ones.
         *
         * @param routePattern the route pattern, as the framework declares it
         * @param spec         the values
         * @return this builder
         */
        public Builder route(String routePattern, PermissionSpec spec) {
            return put(ANY_METHOD, routePattern, spec);
        }

        /**
         * Sets the values for one method of a route, replacing earlier ones.
         *
         * @param method       the HTTP method, in any case
         * @param routePattern the route pattern, as the framework declares it
         * @param spec         the values
         * @return this builder
         */
        public Builder route(String method, String routePattern, PermissionSpec spec) {
            Objects.requireNonNull(method, "method must not be null");
            if (method.isEmpty()) {
                throw new IllegalArgumentException("method must not be empty");
            }
            return put(method.toUpperCase(Locale.ROOT), routePattern, spec);
        }

        private Builder put(String method, String routePattern, PermissionSpec spec) {
            Objects.requireNonNull(routePattern, "routePattern must not be null");
            Objects.requireNonNull(spec, "spec must not be null");
            routes.computeIfAbsent(routePattern, pattern -> new HashMap<>()).put(method, spec);
            return this;
        }

        /**
         * Builds the overrides.
         *
         * @return the overrides
         */
        public RouteOverrides build() {
            return new RouteOverrides(this);
        }
    }
}
