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

    /**
     * Creates the extractor.
     */
    public ReactiveSecurityClaimsExtractor() {
        // Holds no state
    }

    @Override
    public Mono<Map<String, Object>> extract(ServerWebExchange exchange) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .mapNotNull(authentication -> SecurityClaims.of(authentication).orElse(null));
    }

    @Override
    public String toString() {
        return "ReactiveSecurityClaimsExtractor";
    }
}
