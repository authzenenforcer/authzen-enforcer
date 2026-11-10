package io.github.authzenenforcer.core.enforce;

import io.github.authzenenforcer.core.internal.enforce.RouteResourceInferrer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Infers the resource of a request.
 *
 * <p>The {@linkplain #defaults() default} infers it from the route pattern, never from the raw URI.
 * The resource type is the static segment before a path variable, the id is the variable's value:
 * <table>
 *   <caption>Inferred resources</caption>
 *   <tr><th>Route</th><th>Resource</th></tr>
 *   <tr><td>{@code GET /api/v1/documents/{id}}</td><td>{@code {type: "documents", id: <id>}}</td></tr>
 *   <tr><td>{@code GET /documents}</td><td>{@code {type: "collection", id: "documents"}}</td></tr>
 *   <tr><td>{@code GET /tenants/{t}/documents}</td><td>{@code {type: "collection", id: "documents"}},
 *       parent {@code tenants}</td></tr>
 *   <tr><td>{@code GET /tenants/{t}/documents/{id}}</td><td>{@code {type: "documents", id: <id>}},
 *       parent {@code tenants}</td></tr>
 * </table>
 * Enclosing resources go into {@code properties}: {@code parent} holds the nearest one as
 * {@code {type, id}}, {@code ancestors} all of them from the outermost in.
 *
 * <p>Routes it cannot infer from safely are refused with an {@link InferenceException}: a variable
 * that does not follow a static segment ({@code /{tenant}/documents}, {@code /documents/{id}/{v}}),
 * more than one static segment after the last variable, wildcards, catch-all variables and segments
 * that mix text and variables. One static segment after the last variable, as in
 * {@code /documents/{id}/cancel}, stands for a nested collection for {@code GET} and {@code HEAD}; for
 * other methods it is refused unless the action is explicit, and the resource is then the one of the
 * variable. A route whose variable would get the {@linkplain Builder#collectionType(String) collection
 * type}, such as {@code /collection/{id}}, is refused as well, so that callers cannot name a
 * collection by choosing an id. A path variable that is empty, contains a control character or is
 * longer than {@linkplain Builder#maxIdLength(int) the limit} is refused too.
 *
 * <p>Implementations must be thread-safe.
 */
public interface ResourceInferrer {

    /**
     * Infers the resource of a request.
     *
     * @param request     the request
     * @param actionGiven whether the action is set explicitly
     * @return the resource
     * @throws InferenceException if the resource cannot be inferred safely
     */
    InferredResource infer(RequestDescription request, boolean actionGiven);

    /**
     * Checks, before any request arrives, whether the resource of a route can be inferred. The
     * default accepts every route.
     *
     * @param method       the HTTP method
     * @param routePattern the route pattern
     * @param actionGiven  whether the action is set explicitly
     * @throws InferenceException if the resource of the route cannot be inferred
     */
    default void check(String method, String routePattern, boolean actionGiven) {
    }

    /**
     * Returns the route-based inferrer with its default settings.
     *
     * @return the inferrer
     */
    static ResourceInferrer defaults() {
        return Builder.DEFAULTS;
    }

    /**
     * Returns a builder for the route-based inferrer.
     *
     * @return the builder
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for the route-based {@link ResourceInferrer}. Not thread-safe.
     */
    final class Builder {

        /** Default limit for the length of an id taken from a path variable. */
        public static final int DEFAULT_MAX_ID_LENGTH = 1024;

        /** Default type of the resource that stands for a collection. */
        public static final String DEFAULT_COLLECTION_TYPE = "collection";

        private static final ResourceInferrer DEFAULTS = new Builder().build();

        private boolean singularize;
        private final Map<String, String> typeNames = new HashMap<>();
        private String collectionType = DEFAULT_COLLECTION_TYPE;
        private int maxIdLength = DEFAULT_MAX_ID_LENGTH;

        private Builder() {
        }

        /**
         * Turns plural segments into singular resource types, such as {@code documents} into
         * {@code document}. Off by default, so that a segment is the type as it is.
         *
         * <p>The rules are conservative: {@code ies} becomes {@code y}; {@code sses}, {@code xes},
         * {@code zzes}, {@code ches} and {@code shes} lose {@code es}; any other final {@code s} is
         * dropped, except in words ending in {@code ss}, {@code us} and {@code is}. Irregular words
         * come out wrong, such as {@code movies} as {@code movy}; name them with
         * {@link #typeName(String, String)}.
         *
         * @param singularize whether to singularize
         * @return this builder
         */
        public Builder singularize(boolean singularize) {
            this.singularize = singularize;
            return this;
        }

        /**
         * Sets the resource type for a route segment, whether or not segments are singularized.
         *
         * @param segment the segment, such as {@code analyses}
         * @param type    the resource type, such as {@code analysis}
         * @return this builder
         */
        public Builder typeName(String segment, String type) {
            typeNames.put(Objects.requireNonNull(segment, "segment must not be null"),
                    Objects.requireNonNull(type, "type must not be null"));
            return this;
        }

        /**
         * Sets the type of the resource that stands for a collection; its id is the collection's
         * resource type. Defaults to {@value #DEFAULT_COLLECTION_TYPE}.
         *
         * @param collectionType the type
         * @return this builder
         */
        public Builder collectionType(String collectionType) {
            this.collectionType = Objects.requireNonNull(collectionType, "collectionType must not be null");
            return this;
        }

        /**
         * Sets the longest id accepted from a path variable. Defaults to
         * {@value #DEFAULT_MAX_ID_LENGTH} characters.
         *
         * @param maxIdLength a positive number of characters
         * @return this builder
         */
        public Builder maxIdLength(int maxIdLength) {
            if (maxIdLength <= 0) {
                throw new IllegalArgumentException("maxIdLength must be positive");
            }
            this.maxIdLength = maxIdLength;
            return this;
        }

        /**
         * Builds the inferrer.
         *
         * @return the inferrer
         */
        public ResourceInferrer build() {
            return new RouteResourceInferrer(singularize, typeNames, collectionType, maxIdLength);
        }
    }
}
