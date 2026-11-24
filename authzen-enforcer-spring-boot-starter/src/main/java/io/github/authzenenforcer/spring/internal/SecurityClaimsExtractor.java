package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.spring.servlet.ClaimsExtractor;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Optional;

/**
 * Gets the claims from the Spring Security {@link Authentication} of the request; see
 * {@link ClaimsExtractor}.
 */
public final class SecurityClaimsExtractor implements ClaimsExtractor {

    private final boolean authorities;

    /**
     * Creates the extractor.
     */
    public SecurityClaimsExtractor() {
        this(false);
    }

    /**
     * Creates the extractor.
     *
     * @param authorities whether the caller's granted authorities travel with the claims
     */
    public SecurityClaimsExtractor(boolean authorities) {
        this.authorities = authorities;
    }

    @Override
    public Optional<Map<String, Object>> extract(HttpServletRequest request) {
        return SecurityClaims.of(authentication(), authorities);
    }

    private static Authentication authentication() {
        return SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
    }

    @Override
    public String toString() {
        return "SecurityClaimsExtractor{authorities=" + authorities + "}";
    }
}
