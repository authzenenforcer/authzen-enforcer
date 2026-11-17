package io.github.authzenenforcer.spring.reactive;

import io.github.authzenenforcer.core.enforce.EnforcementResult;

import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Answers a request that enforcement did not let through.
 *
 * <p>With Spring Security, the default signals {@code AccessDeniedException} for {@value
 * EnforcementResult#UNAUTHORIZED} and {@value EnforcementResult#FORBIDDEN}: Spring Security's
 * exception handling then lets its authentication entry point send the challenge if the caller is
 * not authenticated, and its access denied handler answer otherwise. The status {@value
 * EnforcementResult#SERVICE_UNAVAILABLE} is signalled as a {@code ResponseStatusException}. Without
 * Spring Security, it signals a {@code ResponseStatusException} with the status of the result, with
 * {@code WWW-Authenticate: Bearer} for {@value EnforcementResult#UNAUTHORIZED}.
 *
 * <p>An application defines a bean of this type to answer differently, for example to log the PDP's
 * reason, which is not sent to the client by default. A handler that completes without an error and
 * without setting an error status leaves the request denied: the status of the result is then
 * signalled for it.
 *
 * <p>Implementations must be thread-safe and must not block.
 */
@FunctionalInterface
public interface ReactiveEnforcementResultHandler {

    /**
     * Answers a request that is not let through.
     *
     * @param exchange the exchange
     * @param result   the result, which is not {@linkplain EnforcementResult#isAllowed() allowed}
     * @return completes when the request is answered, or signals the error that answers it
     */
    Mono<Void> handleDenied(ServerWebExchange exchange, EnforcementResult result);
}
