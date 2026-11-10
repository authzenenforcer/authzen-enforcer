package io.github.authzenenforcer.core.enforce;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Infers the action name from the HTTP method.
 *
 * <p>The {@linkplain #defaults() default} dictionary:
 * <table>
 *   <caption>Default actions</caption>
 *   <tr><th>Method</th><th>Single resource</th><th>Collection</th></tr>
 *   <tr><td>GET, HEAD</td><td>{@code read}</td><td>{@code list}</td></tr>
 *   <tr><td>POST</td><td>{@code create}</td><td>{@code create}</td></tr>
 *   <tr><td>PUT, PATCH</td><td>{@code update}</td><td>{@code update}</td></tr>
 *   <tr><td>DELETE</td><td>{@code delete}</td><td>{@code delete}</td></tr>
 *   <tr><td>OPTIONS</td><td colspan="2">not enforced (CORS preflight)</td></tr>
 * </table>
 * A method outside the dictionary has no action, and its requests are denied unless the action is
 * set explicitly. Actions are looked up in any case, so {@code delete} is enforced like
 * {@code DELETE}; a method is only exempt if it matches an exempt method exactly, since HTTP method
 * tokens are case-sensitive and {@code options} is not a CORS preflight. A POST is not always a create, as in {@code /search}; such endpoints should set
 * their action explicitly.
 *
 * <p>Implementations must be thread-safe.
 */
public interface ActionMapper {

    /**
     * Returns the action for a method.
     *
     * @param method     the HTTP method, in any case
     * @param collection whether the resource stands for a collection
     * @return the action name, or empty if there is none for the method
     */
    Optional<String> action(String method, boolean collection);

    /**
     * Returns whether requests with a method are authorized at all. An explicit action enforces
     * them anyway. By default every method is.
     *
     * <p>An implementation must only exempt the exact method token it means: HTTP method tokens are
     * case-sensitive, and frameworks pass unknown tokens such as {@code options} on to handlers
     * mapped without a method.
     *
     * @param method the HTTP method, exactly as the request named it
     * @return {@code false} to let requests with the method through unchecked
     */
    default boolean enforces(String method) {
        return true;
    }

    /**
     * Returns the default dictionary.
     *
     * @return the mapper
     */
    static ActionMapper defaults() {
        return Builder.DEFAULTS;
    }

    /**
     * Returns a builder that starts from the default dictionary.
     *
     * @return the builder
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for the dictionary of an {@link ActionMapper}, starting from the defaults. Not
     * thread-safe.
     */
    final class Builder {

        private static final ActionMapper DEFAULTS = new Builder().build();

        private final Map<String, String[]> actions = new HashMap<>();
        private final Set<String> notEnforced = new HashSet<>();

        private Builder() {
            map("GET", "read", "list");
            map("HEAD", "read", "list");
            map("POST", "create");
            map("PUT", "update");
            map("PATCH", "update");
            map("DELETE", "delete");
            notEnforced("OPTIONS");
        }

        /**
         * Sets the action of a method for single resources and collections alike, and enforces the
         * method.
         *
         * @param method the HTTP method, in any case
         * @param action the action name
         * @return this builder
         */
        public Builder map(String method, String action) {
            return map(method, action, action);
        }

        /**
         * Sets the actions of a method, and enforces the method.
         *
         * @param method     the HTTP method, in any case
         * @param single     the action for a single resource
         * @param collection the action for a collection
         * @return this builder
         */
        public Builder map(String method, String single, String collection) {
            String key = key(method);
            actions.put(key, new String[] {
                Objects.requireNonNull(single, "single must not be null"),
                Objects.requireNonNull(collection, "collection must not be null")
            });
            notEnforced.removeIf(exempt -> key(exempt).equals(key));
            return this;
        }

        /**
         * Lets requests with exactly this method token through unchecked, unless their action is
         * set explicitly. Other spellings, such as {@code options} for {@code OPTIONS}, are
         * enforced and have no action, so they are denied.
         *
         * @param method the HTTP method, exactly as requests name it
         * @return this builder
         */
        public Builder notEnforced(String method) {
            Objects.requireNonNull(method, "method must not be null");
            actions.remove(key(method));
            notEnforced.add(method);
            return this;
        }

        /**
         * Builds the mapper.
         *
         * @return the mapper
         */
        public ActionMapper build() {
            Map<String, String[]> table = Map.copyOf(actions);
            Set<String> skipped = Set.copyOf(notEnforced);
            return new ActionMapper() {
                @Override
                public Optional<String> action(String method, boolean collection) {
                    String[] pair = table.get(key(method));
                    return pair == null ? Optional.empty() : Optional.of(pair[collection ? 1 : 0]);
                }

                // Exact: options is an unknown method, not a CORS preflight
                @Override
                public boolean enforces(String method) {
                    return !skipped.contains(Objects.requireNonNull(method, "method must not be null"));
                }

                @Override
                public String toString() {
                    StringBuilder out = new StringBuilder("ActionMapper{");
                    table.forEach((method, pair) -> out.append(method).append('=').append(pair[0])
                            .append('/').append(pair[1]).append(", "));
                    return out.append("notEnforced=").append(skipped).append('}').toString();
                }
            };
        }

        private static String key(String method) {
            Objects.requireNonNull(method, "method must not be null");
            return method.toUpperCase(Locale.ROOT);
        }
    }
}
