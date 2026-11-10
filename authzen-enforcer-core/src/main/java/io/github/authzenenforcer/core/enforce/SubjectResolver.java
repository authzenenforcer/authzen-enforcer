package io.github.authzenenforcer.core.enforce;

import io.github.authzenenforcer.core.internal.enforce.ClaimsSubjectResolver;
import io.github.authzenenforcer.core.model.Subject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Infers the subject from the claims of the authenticated caller.
 *
 * <p>The {@linkplain #claims() claims-based} resolver takes the id from the {@code sub} claim, a
 * stable identifier, and the type {@code user}; it copies the claims it is told to into the
 * subject's properties. The {@linkplain #keycloak() Keycloak profile} adds the roles and groups of a
 * Keycloak access token and recognizes service accounts. Claims with personal data, such as
 * {@code email} or {@code preferred_username}, are only sent when mapped explicitly.
 *
 * <p>Implementations must be thread-safe.
 */
@FunctionalInterface
public interface SubjectResolver {

    /**
     * Infers the subject.
     *
     * @param claims the claims of the authenticated caller
     * @return the subject
     * @throws InferenceException if the claims do not identify a subject
     */
    Subject resolve(Map<String, Object> claims);

    /**
     * Returns a builder for a claims-based resolver with id claim {@code sub}, type {@code user} and
     * no properties.
     *
     * @return the builder
     */
    static Builder claims() {
        return new Builder();
    }

    /**
     * Returns a builder for a claims-based resolver with the Keycloak profile: properties
     * {@code roles} from {@code realm_access.roles}, {@code client_roles} from
     * {@code resource_access} and {@code groups} from {@code groups}, and type {@code client} for
     * service accounts, recognized by the {@code client_id} claim that Keycloak adds to tokens of
     * the client credentials grant only. The username is not used: a user may be able to choose
     * one such as {@code service-account-x}.
     *
     * @return the builder
     */
    static Builder keycloak() {
        return new Builder()
                .property("roles", "realm_access", "roles")
                .property("client_roles", "resource_access")
                .property("groups", "groups")
                .serviceAccountType("client");
    }

    /**
     * Builder for a claims-based {@link SubjectResolver}. Not thread-safe.
     */
    final class Builder {

        /** Default claim that holds the subject id. */
        public static final String DEFAULT_ID_CLAIM = "sub";

        /** Default subject type. */
        public static final String DEFAULT_TYPE = "user";

        private String idClaim = DEFAULT_ID_CLAIM;
        private String type = DEFAULT_TYPE;
        private String serviceAccountType;
        private final Map<String, List<String>> properties = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sets the claim that holds the subject id. Defaults to {@value #DEFAULT_ID_CLAIM}.
         *
         * @param idClaim the claim name
         * @return this builder
         */
        public Builder idClaim(String idClaim) {
            this.idClaim = Objects.requireNonNull(idClaim, "idClaim must not be null");
            return this;
        }

        /**
         * Sets the subject type. Defaults to {@value #DEFAULT_TYPE}.
         *
         * @param type the type
         * @return this builder
         */
        public Builder type(String type) {
            this.type = Objects.requireNonNull(type, "type must not be null");
            return this;
        }

        /**
         * Sets the subject type of service accounts, which Keycloak issues tokens to under the
         * client credentials grant. They are recognized by a {@code client_id} claim, which
         * Keycloak adds to those tokens only.
         *
         * <p>Use it only with such an issuer: JWT access tokens following RFC 9068 carry
         * {@code client_id} in every token, so every caller would get this type.
         *
         * @param serviceAccountType the type
         * @return this builder
         */
        public Builder serviceAccountType(String serviceAccountType) {
            this.serviceAccountType = Objects.requireNonNull(serviceAccountType, "serviceAccountType must not be null");
            return this;
        }

        /**
         * Copies a claim into a subject property, replacing an earlier mapping of the property. A
         * claim that is missing is left out; its value must be JSON-compatible.
         *
         * @param name      the property name
         * @param claimPath the claim name, followed by member names to descend into nested
         *                  objects, such as {@code "realm_access", "roles"}
         * @return this builder
         */
        public Builder property(String name, String... claimPath) {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(claimPath, "claimPath must not be null");
            if (claimPath.length == 0) {
                throw new IllegalArgumentException("claimPath must name a claim");
            }
            for (String member : claimPath) {
                Objects.requireNonNull(member, "claimPath must not contain null");
            }
            properties.put(name, List.of(claimPath));
            return this;
        }

        /**
         * Builds the resolver.
         *
         * @return the resolver
         */
        public SubjectResolver build() {
            return new ClaimsSubjectResolver(idClaim, type, serviceAccountType, properties);
        }
    }
}
