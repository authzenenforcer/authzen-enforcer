package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.RoleMapping;
import io.github.authzenenforcer.core.enforce.SubjectResolver;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.spring.AuthZenProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Carries the granted authorities of the caller along with the claims, from the claims extractor
 * to the subject resolver, for {@code authzen.enforcer.subject.authorities}.
 *
 * <p>The authorities travel under {@link #CLAIM} as an instance of this class, which no token and
 * no JSON value can be, so a claim of a token cannot pose as authorities. The name has dots, so a
 * dotted claim path of {@code authzen.enforcer.subject.properties} cannot reach it either. The
 * resolver and the expressions of {@code @CheckPermission} get the claims without it. This class
 * does not depend on Spring Security, so that the auto-configuration loads it without it;
 * {@link SecurityClaims} reads the authorities.
 */
public final class CallerAuthorities {

    /** The name under which the authorities travel with the claims. */
    public static final String CLAIM = "authzen.enforcer.granted-authorities";

    private final List<String> names;

    private CallerAuthorities(List<String> names) {
        this.names = names;
    }

    /**
     * Returns the role mapping that {@code authzen.enforcer.subject.authorities} configures.
     *
     * @param properties the properties
     * @return the mapping, or empty if the authorities are not mapped
     * @throws IllegalStateException if the properties are inconsistent
     */
    public static Optional<RoleMapping> mapping(AuthZenProperties properties) {
        AuthZenProperties.Subject subject = properties.getEnforcer().getSubject();
        return mapping(subject.getAuthorities().getProperty(), subject.getAuthorities().getPrefix(),
                subject.getProperties().keySet());
    }

    /**
     * Returns the role mapping that the properties configure.
     *
     * @param property        {@code authzen.enforcer.subject.authorities.property}, or null
     * @param prefix          {@code authzen.enforcer.subject.authorities.prefix}, or null
     * @param claimProperties the names of the subject properties copied from claims
     * @return the mapping, or empty if the authorities are not mapped
     * @throws IllegalStateException if the properties are inconsistent
     */
    public static Optional<RoleMapping> mapping(String property, String prefix, Set<String> claimProperties) {
        boolean hasPrefix = prefix != null && !prefix.isEmpty();
        if (property == null || property.isEmpty()) {
            if (hasPrefix) {
                throw new IllegalStateException("authzen.enforcer.subject.authorities.prefix needs "
                        + "authzen.enforcer.subject.authorities.property, the subject property to write the roles to");
            }
            return Optional.empty();
        }
        if (claimProperties.contains(property)) {
            throw new IllegalStateException("The subject property " + property + " is set both from a claim, by "
                    + "authzen.enforcer.subject.properties, and from the granted authorities, by "
                    + "authzen.enforcer.subject.authorities.property; choose one");
        }
        RoleMapping.Builder mapping = RoleMapping.toProperty(property);
        if (hasPrefix) {
            mapping.prefix(prefix);
        }
        return Optional.of(mapping.build());
    }

    /**
     * Returns a copy of the claims with the names of the caller's authorities.
     *
     * @param claims the claims
     * @param names  the names of the authorities
     * @return the claims with the authorities
     */
    static Map<String, Object> attach(Map<String, Object> claims, List<String> names) {
        Map<String, Object> copy = new LinkedHashMap<>(claims);
        copy.put(CLAIM, new CallerAuthorities(List.copyOf(names)));
        return copy;
    }

    /**
     * Returns the claims without the authorities.
     *
     * @param claims the claims
     * @return the claims themselves if they carry no authorities, otherwise a copy without them
     */
    public static Map<String, Object> detach(Map<String, Object> claims) {
        if (!claims.containsKey(CLAIM)) {
            return claims;
        }
        Map<String, Object> copy = new LinkedHashMap<>(claims);
        copy.remove(CLAIM);
        return copy;
    }

    /**
     * Returns the authorities that travel with the claims.
     *
     * @param claims the claims
     * @return the names of the authorities, empty if there are none
     */
    static List<String> of(Map<String, Object> claims) {
        return claims.get(CLAIM) instanceof CallerAuthorities authorities ? authorities.names : List.of();
    }

    /**
     * Returns a resolver that resolves the subject from the claims without the authorities, then
     * writes the roles among the authorities into it.
     *
     * @param delegate the resolver of the claims
     * @param mapping  the role mapping
     * @return the resolver
     */
    public static SubjectResolver resolver(SubjectResolver delegate, RoleMapping mapping) {
        return new MappingResolver(Objects.requireNonNull(delegate, "delegate must not be null"),
                Objects.requireNonNull(mapping, "mapping must not be null"));
    }

    @Override
    public String toString() {
        // The names are not personal data, but keep them out of logs of the claims
        return "CallerAuthorities[" + names.size() + "]";
    }

    private record MappingResolver(SubjectResolver delegate, RoleMapping mapping) implements SubjectResolver {

        @Override
        public Subject resolve(Map<String, Object> claims) {
            return mapping.apply(delegate.resolve(detach(claims)), of(claims));
        }
    }
}
