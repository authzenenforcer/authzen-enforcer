package io.github.authzenenforcer.core.enforce;

import io.github.authzenenforcer.core.model.Subject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Writes the roles that the application's framework granted to the caller into a subject property,
 * for PDPs that evaluate the roles a request carries instead of looking them up. The framework
 * integration supplies the granted names: the granted authorities of Spring Security, or the roles
 * of a Quarkus {@code SecurityIdentity}.
 *
 * <pre>{@code
 * RoleMapping mapping = RoleMapping.toProperty("roles").prefix("ROLE_").build();
 * mapping.roles(List.of("ROLE_admin", "SCOPE_read", "ROLE_editor"));   // [admin, editor]
 * }</pre>
 *
 * <p>With a prefix, only the names that start with it count, without it, and a name that is the
 * prefix alone is left out; without a prefix, every name counts as it is. Names keep their order,
 * and a repeated one counts once. The property holds the roles as a JSON array of strings, and is
 * left out when there are none, as a claim that is missing is. The mapping owns the property: a
 * property of the same name that the subject already has, for example from a claim, is replaced by
 * the roles or, without roles, removed, so that the PDP never takes other values for them. Its name
 * is used as it is: a name with a dot, such as {@code cerbos.roles}, is one property, not a nested
 * object.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class RoleMapping {

    private final String property;
    private final String prefix;

    private RoleMapping(Builder builder) {
        this.property = builder.property;
        this.prefix = builder.prefix;
    }

    /**
     * Returns a builder for a mapping into the given subject property.
     *
     * @param property the name of the subject property, not empty
     * @return the builder
     * @throws IllegalArgumentException if the name is empty
     */
    public static Builder toProperty(String property) {
        return new Builder(property);
    }

    /**
     * Returns the name of the subject property.
     *
     * @return the name
     */
    public String getProperty() {
        return property;
    }

    /**
     * Returns the prefix that selects the names that count as roles.
     *
     * @return the prefix, or empty if every name counts
     */
    public Optional<String> getPrefix() {
        return Optional.ofNullable(prefix);
    }

    /**
     * Returns the roles among the names granted to the caller.
     *
     * @param granted the names, such as granted authorities; null elements are skipped
     * @return the roles, in the order of the names, without repeats
     */
    public List<String> roles(Collection<String> granted) {
        Objects.requireNonNull(granted, "granted must not be null");
        Set<String> roles = new LinkedHashSet<>();
        for (String name : granted) {
            if (name == null) {
                continue;
            }
            if (prefix == null) {
                roles.add(name);
            } else if (name.startsWith(prefix) && name.length() > prefix.length()) {
                roles.add(name.substring(prefix.length()));
            }
        }
        return List.copyOf(roles);
    }

    /**
     * Returns the subject with the roles among the granted names in the property. The roles replace
     * a property of the same name that the subject already has; without roles, such a property is
     * removed, and a subject without one is returned as it is.
     *
     * @param subject the subject, such as one a {@link SubjectResolver} inferred from the claims
     * @param granted the names granted to the caller
     * @return the subject with the roles
     */
    public Subject apply(Subject subject, Collection<String> granted) {
        Objects.requireNonNull(subject, "subject must not be null");
        List<String> roles = roles(granted);
        if (roles.isEmpty() && !subject.getProperties().containsKey(property)) {
            return subject;
        }
        Map<String, Object> properties = new LinkedHashMap<>(subject.getProperties());
        if (roles.isEmpty()) {
            properties.remove(property);
        } else {
            properties.put(property, roles);
        }
        return Subject.builder()
                .type(subject.getType())
                .id(subject.getId())
                .properties(properties)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof RoleMapping other && property.equals(other.property)
                && Objects.equals(prefix, other.prefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(property, prefix);
    }

    @Override
    public String toString() {
        return "RoleMapping{property=" + property + ", prefix=" + prefix + "}";
    }

    /**
     * Builder for a {@link RoleMapping}. Not thread-safe.
     */
    public static final class Builder {

        private final String property;
        private String prefix;

        private Builder(String property) {
            Objects.requireNonNull(property, "property must not be null");
            if (property.isEmpty()) {
                throw new IllegalArgumentException("property must not be empty");
            }
            this.property = property;
        }

        /**
         * Counts only the names that start with the prefix as roles, without it, such as
         * {@code ROLE_} for the roles of Spring Security. By default, every name counts.
         *
         * @param prefix the prefix, not empty
         * @return this builder
         * @throws IllegalArgumentException if the prefix is empty
         */
        public Builder prefix(String prefix) {
            Objects.requireNonNull(prefix, "prefix must not be null");
            if (prefix.isEmpty()) {
                throw new IllegalArgumentException("prefix must not be empty; leave it unset to count every name");
            }
            this.prefix = prefix;
            return this;
        }

        /**
         * Builds the mapping.
         *
         * @return the mapping
         */
        public RoleMapping build() {
            return new RoleMapping(this);
        }
    }
}
