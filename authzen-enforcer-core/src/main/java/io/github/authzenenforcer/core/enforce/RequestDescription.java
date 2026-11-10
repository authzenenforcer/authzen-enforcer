package io.github.authzenenforcer.core.enforce;

import io.github.authzenenforcer.core.internal.text.LogText;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An HTTP request as far as enforcement needs it, independent of the web framework.
 *
 * <p>The route pattern is the one the framework matched, such as {@code /documents/{id}}, never the
 * raw request URI: inferring from the URI would expose enforcement to path normalization tricks.
 * Path variables are the values the framework extracted for that pattern.
 *
 * <p>The claims describe the authenticated caller, such as the claims of a JWT access token. Without
 * claims the caller is anonymous. Claims may have any type, such as an {@code Instant} for
 * {@code exp}; only those a {@link SubjectResolver} copies into the subject must be JSON-compatible.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class RequestDescription {

    private final String method;
    private final String routePattern;
    private final Map<String, String> pathVariables;
    private final Map<String, Object> claims;

    private RequestDescription(Builder builder) {
        this.method = Objects.requireNonNull(builder.method, "method is required");
        this.routePattern = Objects.requireNonNull(builder.routePattern, "routePattern is required");
        this.pathVariables = Map.copyOf(builder.pathVariables);
        this.claims = builder.claims != null ? Collections.unmodifiableMap(new LinkedHashMap<>(builder.claims)) : null;
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
     * Returns the HTTP method.
     *
     * @return the method, as the framework reported it
     */
    public String getMethod() {
        return method;
    }

    /**
     * Returns the route pattern the framework matched.
     *
     * @return the route pattern
     */
    public String getRoutePattern() {
        return routePattern;
    }

    /**
     * Returns the path variables of the route pattern.
     *
     * @return an unmodifiable map from variable name to value
     */
    public Map<String, String> getPathVariables() {
        return pathVariables;
    }

    /**
     * Returns the claims of the authenticated caller.
     *
     * @return an unmodifiable map, or empty if the caller is anonymous
     */
    public Optional<Map<String, Object>> getClaims() {
        return Optional.ofNullable(claims);
    }

    /**
     * Returns a description for logging. Path variable values and claims are masked, since they may
     * hold personal data; names are shown. The method, which the caller chooses, is escaped.
     */
    @Override
    public String toString() {
        return "RequestDescription{method=" + LogText.of(method) + ", routePattern=" + LogText.of(routePattern)
                + ", pathVariables=" + pathVariables.keySet()
                + ", claims=" + (claims != null ? claims.keySet() : "anonymous") + "}";
    }

    /**
     * Builder for {@link RequestDescription}. Not thread-safe.
     */
    public static final class Builder {

        private String method;
        private String routePattern;
        private final Map<String, String> pathVariables = new LinkedHashMap<>();
        private Map<String, ?> claims;

        private Builder() {
        }

        /**
         * Sets the HTTP method. Required.
         *
         * @param method the method
         * @return this builder
         */
        public Builder method(String method) {
            this.method = Objects.requireNonNull(method, "method must not be null");
            return this;
        }

        /**
         * Sets the route pattern the framework matched. Required.
         *
         * @param routePattern the route pattern, such as {@code /documents/{id}}
         * @return this builder
         */
        public Builder routePattern(String routePattern) {
            this.routePattern = Objects.requireNonNull(routePattern, "routePattern must not be null");
            return this;
        }

        /**
         * Adds a path variable, replacing any previous value with the same name.
         *
         * @param name  the variable name in the route pattern
         * @param value the value the framework extracted
         * @return this builder
         */
        public Builder pathVariable(String name, String value) {
            pathVariables.put(Objects.requireNonNull(name, "name must not be null"),
                    Objects.requireNonNull(value, "value must not be null"));
            return this;
        }

        /**
         * Adds path variables, replacing previous values with the same names.
         *
         * @param variables the variables
         * @return this builder
         */
        public Builder pathVariables(Map<String, String> variables) {
            Objects.requireNonNull(variables, "variables must not be null").forEach(this::pathVariable);
            return this;
        }

        /**
         * Sets the claims of the authenticated caller. Without claims the caller is anonymous. The
         * map is copied, its values are not, so they must not change afterwards.
         *
         * @param claims the claims
         * @return this builder
         */
        public Builder claims(Map<String, ?> claims) {
            this.claims = Objects.requireNonNull(claims, "claims must not be null");
            return this;
        }

        /**
         * Builds the description.
         *
         * @return the description
         * @throws NullPointerException if the method or route pattern is missing
         */
        public RequestDescription build() {
            return new RequestDescription(this);
        }
    }
}
