package io.github.authzenenforcer.spring.internal;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gets the claims of a Spring Security {@link Authentication}, for Spring MVC and Spring WebFlux:
 * the claims of a JWT, the attributes of an opaque token or of an OAuth 2.0 login, and otherwise
 * only {@code sub}, set to the name of the authenticated principal. On request, the names of the
 * caller's granted authorities travel with them; see {@link CallerAuthorities}.
 */
final class SecurityClaims {

    private static final String SUBJECT_CLAIM = "sub";

    private static final boolean OAUTH2_CORE = ClassUtils.isPresent(
            "org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal",
            SecurityClaims.class.getClassLoader());
    private static final boolean RESOURCE_SERVER = ClassUtils.isPresent(
            "org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken",
            SecurityClaims.class.getClassLoader());

    private SecurityClaims() {
    }

    /**
     * Gets the claims.
     *
     * @param authentication the authentication, or {@code null}
     * @return the claims, or empty if the caller is anonymous
     */
    static Optional<Map<String, Object>> of(Authentication authentication) {
        return of(authentication, false);
    }

    /**
     * Gets the claims, with the names of the caller's granted authorities if asked to.
     *
     * @param authentication the authentication, or {@code null}
     * @param authorities    whether the authorities travel with the claims
     * @return the claims, or empty if the caller is anonymous
     */
    static Optional<Map<String, Object>> of(Authentication authentication, boolean authorities) {
        Optional<Map<String, Object>> claims = claims(authentication);
        return authorities ? claims.map(found -> CallerAuthorities.attach(found, names(authentication))) : claims;
    }

    private static List<String> names(Authentication authentication) {
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        List<String> names = new ArrayList<>();
        if (authorities != null) {
            for (GrantedAuthority authority : authorities) {
                String name = authority == null ? null : authority.getAuthority();
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static Optional<Map<String, Object>> claims(Authentication authentication) {
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
}
