package io.github.authzenenforcer.spring.reactive;

import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Gets the claims of the authenticated caller of a request, from which the subject is inferred.
 *
 * <p>With Spring Security, the default takes them from the {@code Authentication} of the reactive
 * security context: the claims of a JWT, the attributes of an opaque token or of an OAuth 2.0
 * login, and otherwise only {@code sub}, set to the name of the authenticated principal. Without
 * Spring Security, every caller is anonymous unless the application defines a bean of this type.
 *
 * <p>Implementations must be thread-safe and must not block.
 */
@FunctionalInterface
public interface ReactiveClaimsExtractor {

    /**
     * Gets the claims of the caller.
     *
     * @param exchange the exchange
     * @return the claims, with JSON-compatible values, or an empty {@code Mono} if the caller is
     *         anonymous
     */
    Mono<Map<String, Object>> extract(ServerWebExchange exchange);
}
