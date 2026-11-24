package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.spring.reactive.ReactiveClaimsExtractor;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Gets the claims from the Spring Security {@code Authentication} of the reactive security
 * context, as {@link SecurityClaimsExtractor} does from the servlet one; see
 * {@link ReactiveClaimsExtractor}.
 */
public final class ReactiveSecurityClaimsExtractor implements ReactiveClaimsExtractor {

    private final boolean authorities;

    /**
     * Creates the extractor.
     */
    public ReactiveSecurityClaimsExtractor() {
        this(false);
    }

    /**
     * Creates the extractor.
     *
     * @param authorities whether the caller's granted authorities travel with the claims
     */
    public ReactiveSecurityClaimsExtractor(boolean authorities) {
        this.authorities = authorities;
    }

    @Override
    public Mono<Map<String, Object>> extract(ServerWebExchange exchange) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .mapNotNull(authentication -> SecurityClaims.of(authentication, authorities).orElse(null));
    }

    @Override
    public String toString() {
        return "ReactiveSecurityClaimsExtractor{authorities=" + authorities + "}";
    }
}
