package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.EnforcementResult;
import io.github.authzenenforcer.spring.reactive.ReactiveEnforcementResultHandler;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Signals the status of the result, for applications without Spring Security.
 */
public final class ReactiveStatusResultHandler implements ReactiveEnforcementResultHandler {

    /**
     * Creates the handler.
     */
    public ReactiveStatusResultHandler() {
        // Holds no state
    }

    @Override
    public Mono<Void> handleDenied(ServerWebExchange exchange, EnforcementResult result) {
        int status = result.getStatus().orElse(EnforcementResult.FORBIDDEN);
        if (status == EnforcementResult.UNAUTHORIZED) {
            // RFC 9110 requires a challenge with 401. Set on the response, which the error handlers
            // of WebFlux and Spring Boot keep, rather than on the exception, which only some copy
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return Mono.error(new ResponseStatusException(HttpStatusCode.valueOf(status)));
    }

    @Override
    public String toString() {
        return "ReactiveStatusResultHandler";
    }
}
