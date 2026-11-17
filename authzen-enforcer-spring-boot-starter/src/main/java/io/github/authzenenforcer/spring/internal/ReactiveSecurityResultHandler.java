package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.EnforcementResult;
import io.github.authzenenforcer.spring.reactive.ReactiveEnforcementResultHandler;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Leaves the response to Spring Security's exception handling. Its reactive exception handling
 * handles only {@link AccessDeniedException}: it lets the authentication entry point send its
 * challenge if the caller is not authenticated, as for {@value EnforcementResult#UNAUTHORIZED}, and
 * the access denied handler answer otherwise.
 */
public final class ReactiveSecurityResultHandler implements ReactiveEnforcementResultHandler {

    /**
     * Creates the handler.
     */
    public ReactiveSecurityResultHandler() {
        // Holds no state
    }

    @Override
    public Mono<Void> handleDenied(ServerWebExchange exchange, EnforcementResult result) {
        int status = result.getStatus().orElse(EnforcementResult.FORBIDDEN);
        if (status == EnforcementResult.SERVICE_UNAVAILABLE) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        }
        return Mono.error(new AccessDeniedException(status == EnforcementResult.UNAUTHORIZED
                ? "Authentication is required"
                : "Access is denied"));
    }

    @Override
    public String toString() {
        return "ReactiveSecurityResultHandler";
    }
}
