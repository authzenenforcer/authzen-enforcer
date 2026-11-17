package io.github.authzenenforcer.spring.internal;

import io.github.authzenenforcer.core.enforce.EnforcementResult;
import io.github.authzenenforcer.core.enforce.Enforcer;
import io.github.authzenenforcer.spring.reactive.ReactiveClaimsExtractor;
import io.github.authzenenforcer.spring.reactive.ReactiveEnforcementResultHandler;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerAdapter;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.Map;
import java.util.Optional;

/**
 * Asks the {@link Enforcer} before an enforced handler method runs, and lets the
 * {@link ReactiveEnforcementResultHandler} answer requests it does not let through.
 *
 * <p>Ordered first, it is the handler adapter the dispatcher picks for handler methods: it decides,
 * then hands the request to the adapter that runs them. It runs where a Spring MVC handler
 * interceptor does: after the handler is mapped, so that it decides about the very handler that
 * runs, and before arguments are resolved and the body is read.
 */
public final class PermissionHandlerAdapter implements HandlerAdapter, Ordered {

    private static final System.Logger LOGGER = System.getLogger(PermissionHandlerAdapter.class.getName());

    // Answers an OPTIONS request that no mapping declares with the Allow header; no application code
    // runs, and it has no route pattern to infer from
    private static final String OPTIONS_HANDLER =
            "org.springframework.web.reactive.result.method.RequestMappingInfoHandlerMapping$HttpOptionsHandler";

    private static final MultiValueMap<String, String> NO_FORM = new LinkedMultiValueMap<>(0);

    private final Enforcer enforcer;
    private final HandlerPermissions permissions;
    private final ReactiveClaimsExtractor claimsExtractor;
    private final ReactiveEnforcementResultHandler resultHandler;
    private final HandlerAdapter handlerMethods;
    private final Scheduler scheduler;
    private final PermissionRequests requests = new PermissionRequests(LOGGER);

    /**
     * Creates the adapter.
     *
     * @param enforcer        the enforcer
     * @param permissions     how handlers are enforced
     * @param claimsExtractor gets the caller's claims
     * @param resultHandler   answers requests that are not let through
     * @param handlerMethods  the adapter that runs handler methods
     * @param scheduler       starts every decision, so that what the application plugs into it,
     *                        such as a credential provider, cannot block the thread of the request
     */
    public PermissionHandlerAdapter(Enforcer enforcer, HandlerPermissions permissions,
            ReactiveClaimsExtractor claimsExtractor, ReactiveEnforcementResultHandler resultHandler,
            HandlerAdapter handlerMethods, Scheduler scheduler) {
        this.enforcer = enforcer;
        this.permissions = permissions;
        this.claimsExtractor = claimsExtractor;
        this.resultHandler = resultHandler;
        this.handlerMethods = handlerMethods;
        this.scheduler = scheduler;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean supports(Object handler) {
        return handler instanceof HandlerMethod && handlerMethods.supports(handler);
    }

    @Override
    public Mono<HandlerResult> handle(ServerWebExchange exchange, Object handler) {
        HandlerMethod method = (HandlerMethod) handler;
        if (OPTIONS_HANDLER.equals(method.getBeanType().getName())) {
            return handlerMethods.handle(exchange, handler);
        }
        String routePattern = routePattern(exchange);
        HandlerPermission permission = permissions.get(method);
        if (!permission.enforces(routePattern)) {
            return handlerMethods.handle(exchange, handler);
        }
        return decide(exchange, permission, routePattern).flatMap(result -> result.isAllowed()
                ? handlerMethods.handle(exchange, handler)
                : denied(exchange, result));
    }

    private Mono<EnforcementResult> decide(ServerWebExchange exchange, HandlerPermission permission,
            String routePattern) {
        if (routePattern == null) {
            return Mono.just(requests.noRoutePattern(permission));
        }
        Mono<Optional<Map<String, Object>>> claims = Mono.defer(() -> claimsExtractor.extract(exchange))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorMap(e -> new Refusal(requests.claimsFailed(e)));
        return claims
                .zipWith(form(exchange, permission))
                .flatMap(values -> ask(exchange, permission, routePattern, values.getT1(), values.getT2()))
                .onErrorResume(Refusal.class, refusal -> Mono.just(refusal.result));
    }

    // Read only for expressions that read parameters: the form is the body, read in full. Spring
    // WebFlux caches it, and reads form bodies only this way, so that handlers still get it
    private Mono<MultiValueMap<String, String>> form(ServerWebExchange exchange, HandlerPermission permission) {
        if (!permission.readsParameters()) {
            return Mono.just(NO_FORM);
        }
        return exchange.getFormData().onErrorMap(e -> new Refusal(requests.refused("Reading the form of a request"
                + " to " + permission.getHandler() + " failed with " + e.getClass().getName())));
    }

    private Mono<EnforcementResult> ask(ServerWebExchange exchange, HandlerPermission permission,
            String routePattern, Optional<Map<String, Object>> claims, MultiValueMap<String, String> form) {
        return Mono.defer(() -> {
            PermissionRequests.Prepared prepared = requests.prepare(permission,
                    exchange.getRequest().getMethod().name(), routePattern, pathVariables(exchange),
                    new ReactiveRequestView(exchange.getRequest(), form), claims);
            if (prepared.refusal() != null) {
                return Mono.just(prepared.refusal());
            }
            // Cancelling the subscription, as when the client goes away, cancels the call to the PDP
            return Mono.fromCompletionStage(enforcer.enforceAsync(prepared.description(), prepared.spec()));
        }).subscribeOn(scheduler);
    }

    private Mono<HandlerResult> denied(ServerWebExchange exchange, EnforcementResult result) {
        return Mono.defer(() -> resultHandler.handleDenied(exchange, result)).then(Mono.defer(() -> {
            // A handler that neither signalled an error nor set an error status must not let the
            // request through
            ServerHttpResponse response = exchange.getResponse();
            HttpStatusCode status = response.getStatusCode();
            if (response.isCommitted() || (status != null && status.isError())) {
                return Mono.empty();
            }
            return Mono.error(new ResponseStatusException(
                    HttpStatusCode.valueOf(result.getStatus().orElse(EnforcementResult.FORBIDDEN))));
        }));
    }

    private static String routePattern(ServerWebExchange exchange) {
        Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof PathPattern pathPattern ? pathPattern.getPatternString() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(ServerWebExchange exchange) {
        Object variables = exchange.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    @Override
    public String toString() {
        return "PermissionHandlerAdapter{enforcer=" + enforcer + ", claimsExtractor=" + claimsExtractor
                + ", resultHandler=" + resultHandler + "}";
    }

    // Carries a refusal past the steps after the one that refused; never leaves this class
    private static final class Refusal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient EnforcementResult result;

        Refusal(EnforcementResult result) {
            super("Refused without asking the PDP", null, false, false);
            this.result = result;
        }
    }
}
