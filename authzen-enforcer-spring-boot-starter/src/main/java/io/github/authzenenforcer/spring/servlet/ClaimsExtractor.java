package io.github.authzenenforcer.spring.servlet;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.Optional;

/**
 * Gets the claims of the authenticated caller of a request, from which the subject is inferred.
 *
 * <p>With Spring Security, the default takes them from the {@code Authentication}: the claims of a
 * JWT, the attributes of an opaque token or of an OAuth 2.0 login, and otherwise only {@code sub},
 * set to the name of the authenticated principal. Without Spring Security, every caller is
 * anonymous unless the application defines a bean of this type.
 *
 * <p>Implementations must be thread-safe.
 */
@FunctionalInterface
public interface ClaimsExtractor {

    /**
     * Gets the claims of the caller.
     *
     * @param request the request
     * @return the claims, with JSON-compatible values, or empty if the caller is anonymous
     */
    Optional<Map<String, Object>> extract(HttpServletRequest request);
}
