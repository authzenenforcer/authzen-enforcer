package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.spring.servlet.ClaimsExtractor;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.util.ClassUtils;

import java.util.Map;
import java.util.Optional;

/**
 * Gets the claims from the Spring Security {@link Authentication} of the request; see
 * {@link ClaimsExtractor}.
 */
public final class SecurityClaimsExtractor implements ClaimsExtractor {

    private static final String SUBJECT_CLAIM = "sub";

    private static final boolean OAUTH2_CORE = ClassUtils.isPresent(
            "org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal",
            SecurityClaimsExtractor.class.getClassLoader());
    private static final boolean RESOURCE_SERVER = ClassUtils.isPresent(
            "org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken",
            SecurityClaimsExtractor.class.getClassLoader());

    /**
     * Creates the extractor.
     */
    public SecurityClaimsExtractor() {
        // Holds no state
    }

    @Override
    public Optional<Map<String, Object>> extract(HttpServletRequest request) {
        return claims(authentication());
    }

    /**
     * Returns the authentication of the current request.
     *
     * @return the authentication, or {@code null} if there is none
     */
    public static Authentication authentication() {
        return SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
    }

    static Optional<Map<String, Object>> claims(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        Map<String, Object> attributes = oauth2Attributes(authentication);
        if (attributes != null) {
            return Optional.of(Claims.of(attributes));
        }
        String name = authentication.getName();
        return name == null || name.isEmpty() ? Optional.empty() : Optional.of(Map.of(SUBJECT_CLAIM, name));
    }

    // In classes of their own, so that this class loads without the OAuth 2.0 modules
    private static Map<String, Object> oauth2Attributes(Authentication authentication) {
        if (RESOURCE_SERVER) {
            Map<String, Object> claims = ResourceServer.claims(authentication);
            if (claims != null) {
                return claims;
            }
        }
        return OAUTH2_CORE ? OAuth2Core.attributes(authentication) : null;
    }

    private static final class ResourceServer {

        static Map<String, Object> claims(Authentication authentication) {
            // A JWT or an introspected opaque token
            return authentication instanceof AbstractOAuth2TokenAuthenticationToken<?> token
                    ? token.getTokenAttributes()
                    : null;
        }
    }

    private static final class OAuth2Core {

        static Map<String, Object> attributes(Authentication authentication) {
            // Such as the user of an OAuth 2.0 or OpenID Connect login
            return authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal
                    ? principal.getAttributes()
                    : null;
        }
    }

    @Override
    public String toString() {
        return "SecurityClaimsExtractor";
    }
}
