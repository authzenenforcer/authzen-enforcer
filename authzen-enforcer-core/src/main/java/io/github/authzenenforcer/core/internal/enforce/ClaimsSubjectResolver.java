package io.github.authzenenforcer.core.internal.enforce;

import io.github.authzenenforcer.core.enforce.InferenceException;
import io.github.authzenenforcer.core.enforce.SubjectResolver;
import io.github.authzenenforcer.core.model.Subject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Infers the subject from claims; see {@link SubjectResolver}.
 */
public final class ClaimsSubjectResolver implements SubjectResolver {

    // Keycloak's service_account client scope sets it only for the client credentials grant
    private static final String CLIENT_ID_CLAIM = "client_id";

    private final String idClaim;
    private final String type;
    private final String serviceAccountType;
    private final Map<String, List<String>> properties;

    /**
     * Creates a resolver.
     *
     * @param idClaim            the claim that holds the id
     * @param type               the subject type
     * @param serviceAccountType the type of service accounts, or {@code null} not to recognize them
     * @param properties         claim paths by property name
     */
    public ClaimsSubjectResolver(String idClaim, String type, String serviceAccountType,
            Map<String, List<String>> properties) {
        this.idClaim = idClaim;
        this.type = type;
        this.serviceAccountType = serviceAccountType;
        this.properties = new LinkedHashMap<>(properties);
    }

    @Override
    public Subject resolve(Map<String, Object> claims) {
        if (!(claims.get(idClaim) instanceof String id) || id.isEmpty()) {
            throw new InferenceException("The caller's claims have no " + idClaim + " claim to take the subject id from",
                    true);
        }
        Subject.Builder subject = Subject.builder().type(isServiceAccount(claims) ? serviceAccountType : type).id(id);
        properties.forEach((name, path) -> {
            Object value = at(claims, path);
            if (value != null) {
                subject.property(name, value);
            }
        });
        try {
            return subject.build();
        } catch (IllegalArgumentException e) {
            // The message names the type of the value, never the value
            throw new InferenceException("A claim mapped to a subject property is not JSON-compatible: "
                    + e.getMessage(), true);
        }
    }

    private boolean isServiceAccount(Map<String, Object> claims) {
        // Not preferred_username: users may choose a name such as service-account-x themselves
        return serviceAccountType != null && claims.get(CLIENT_ID_CLAIM) instanceof String;
    }

    private static Object at(Map<String, Object> claims, List<String> path) {
        Object current = claims;
        for (String member : path) {
            if (!(current instanceof Map<?, ?> object)) {
                return null;
            }
            current = object.get(member);
        }
        return current;
    }

    @Override
    public String toString() {
        return "ClaimsSubjectResolver{idClaim=" + idClaim + ", type=" + type + ", serviceAccountType="
                + serviceAccountType + ", properties=" + properties + "}";
    }
}
