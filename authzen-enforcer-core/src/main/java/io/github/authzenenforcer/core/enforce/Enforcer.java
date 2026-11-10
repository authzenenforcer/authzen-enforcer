package io.github.authzenenforcer.core.enforce;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.event.EnforcementCompleted;
import io.github.authzenenforcer.core.event.EventContent;
import io.github.authzenenforcer.core.event.ListenerDrops;
import io.github.authzenenforcer.core.internal.client.CallResult;
import io.github.authzenenforcer.core.internal.event.EventDispatcher;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.spi.PepListener;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Decides whether a request may proceed: infers its evaluation request with a
 * {@link RequestInferrer}, asks the PDP through an {@link AuthZenClient} and applies an
 * {@link EnforcementPolicy}.
 *
 * <pre>{@code
 * Enforcer enforcer = Enforcer.builder(client).build();
 * EnforcementResult result = enforcer.enforce(request, PermissionSpec.none());
 * if (!result.isAllowed()) {
 *     response.sendError(result.getStatus().getAsInt());
 * }
 * }</pre>
 *
 * <p>The enforcer fails closed. It never throws for a request that cannot be decided: a request
 * that cannot be inferred is {@linkplain EnforcementResult.Outcome#REFUSED refused}, an anonymous
 * caller is {@linkplain EnforcementResult.Outcome#UNAUTHENTICATED unauthenticated}, and a request
 * without a decision, whether the PDP failed or an inference component threw, has
 * {@linkplain EnforcementResult.Outcome#FAILED failed}; none of them is let through. Only a
 * {@linkplain EnforcementPolicy.Builder#failOpen(boolean) fail-open policy} lets a request through
 * whose call to the PDP failed.
 *
 * <p>A caller that gives up stops the call to the PDP: cancelling the stage of
 * {@link #enforceAsync}, or interrupting the thread that waits in {@link #enforce}, aborts the
 * exchange as described in {@link AuthZenClient#evaluateAsync}. Such a request gets no result;
 * the caller gets a {@link CancellationException} and must not let it through.
 *
 * <p>Instances are thread-safe and meant to be long-lived: create one per client and share it.
 */
public final class Enforcer {

    private static final System.Logger LOGGER = System.getLogger(Enforcer.class.getName());

    private final AuthZenClient client;
    private final RequestInferrer inferrer;
    private final EnforcementPolicy policy;
    private final EventDispatcher events;
    private final EventDispatcher.Throttle failOpenWarnings = new EventDispatcher.Throttle(System::nanoTime);
    private final EventDispatcher.Throttle componentWarnings = new EventDispatcher.Throttle(System::nanoTime);

    private Enforcer(Builder builder) {
        this.client = builder.client;
        this.inferrer = builder.inferrer;
        this.policy = builder.policy;
        this.events = EventDispatcher.create(builder.listeners, builder.listenerExecutor,
                AuthZenClient.Builder.DEFAULT_LISTENER_QUEUE_CAPACITY,
                AuthZenClient.Builder.DEFAULT_LISTENER_QUEUE_MAX_BYTES, EventContent.defaults());
        if (policy.isFailOpen()) {
            LOGGER.log(Level.WARNING, "Fail-open is enabled: requests are let through without a decision"
                    + " whenever the PDP gives none");
        }
    }

    /**
     * Returns a new builder.
     *
     * @param client the client that asks the PDP
     * @return the builder
     */
    public static Builder builder(AuthZenClient client) {
        return new Builder(Objects.requireNonNull(client, "client must not be null"));
    }

    /**
     * Decides about a request, waiting for the PDP if it has to be asked.
     *
     * @param request the request
     * @param spec    the explicit values of the endpoint
     * @return the result
     * @throws CancellationException if the thread was interrupted while it waited for the PDP; the
     *                               interrupt flag stays set and the call to the PDP is stopped
     */
    public EnforcementResult enforce(RequestDescription request, PermissionSpec spec) {
        long start = System.nanoTime();
        Inferred inferred = infer(request, spec);
        if (inferred.result != null) {
            return publish(request, start, inferred.result);
        }
        CompletableFuture<EvaluationResponse> call;
        try {
            call = client.evaluateAsync(inferred.evaluation).toCompletableFuture();
        } catch (RuntimeException e) {
            return publish(request, start, failed(inferred.evaluation, e));
        }
        try {
            return publish(request, start, decided(inferred.evaluation, call.get(), null));
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            publishCancelled(request, start);
            CancellationException cancelled = new CancellationException("Interrupted while waiting for the PDP");
            cancelled.initCause(e);
            throw cancelled;
        } catch (ExecutionException | CancellationException e) {
            // A cancel that did not come from this caller is a failure like any other
            return publish(request, start, decided(inferred.evaluation, null, e));
        }
    }

    /**
     * Decides about a request without blocking.
     *
     * <p>The stage cannot be completed by its holder. Cancelling it, or the future that
     * {@code toCompletableFuture()} returns, stops the call to the PDP, as described in
     * {@link AuthZenClient#evaluateAsync}; reactive libraries cancel it that way when their
     * subscriber cancels.
     *
     * @param request the request
     * @param spec    the explicit values of the endpoint
     * @return a stage completed with the result, never exceptionally unless it was cancelled
     */
    public CompletionStage<EnforcementResult> enforceAsync(RequestDescription request, PermissionSpec spec) {
        long start = System.nanoTime();
        Inferred inferred = infer(request, spec);
        AtomicReference<CompletableFuture<EvaluationResponse>> call = new AtomicReference<>();
        CallResult<EnforcementResult> result = new CallResult<>(() -> {
            CompletableFuture<EvaluationResponse> current = call.get();
            if (current != null) {
                current.cancel(true);
            }
        });
        if (inferred.result != null) {
            result.succeed(publish(request, start, inferred.result));
            return result;
        }
        try {
            call.set(client.evaluateAsync(inferred.evaluation).toCompletableFuture());
        } catch (RuntimeException e) {
            result.succeed(publish(request, start, failed(inferred.evaluation, e)));
            return result;
        }
        // A cancel before the line above found no call to stop
        if (result.isCancelled()) {
            call.get().cancel(true);
        }
        call.get().whenComplete((response, error) -> {
            EnforcementResult outcome = decided(inferred.evaluation, response, error);
            if (result.succeed(outcome)) {
                publish(request, start, outcome);
            } else {
                publishCancelled(request, start);
            }
        });
        return result;
    }

    /**
     * Checks, before any request arrives, whether requests to a route can be inferred; see
     * {@link RequestInferrer#check(String, String, PermissionSpec)}. Framework integrations call
     * this at startup for every protected route and log what it returns as a warning.
     *
     * @param method       the HTTP method
     * @param routePattern the route pattern
     * @param spec         the explicit values of the endpoint
     * @return why requests to the route will be refused, or empty if they can be inferred
     */
    public Optional<String> check(String method, String routePattern, PermissionSpec spec) {
        return inferrer.check(method, routePattern, spec);
    }

    /**
     * Returns how many events the listeners of this enforcer did not get because their queues
     * were full.
     *
     * @return the counts per listener and event type
     */
    public ListenerDrops listenerDrops() {
        return events.drops();
    }

    /**
     * Returns the policy.
     *
     * @return the policy
     */
    public EnforcementPolicy getPolicy() {
        return policy;
    }

    private Inferred infer(RequestDescription request, PermissionSpec spec) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        Inference inference;
        try {
            inference = inferrer.infer(request, spec);
        } catch (RuntimeException e) {
            // A faulty custom component, such as a subject resolver; fail-open is for the PDP only
            if (componentWarnings.allow()) {
                LOGGER.log(Level.WARNING, "Inferring the evaluation request failed; the request is denied"
                        + " (logged at most once a minute)", e);
            }
            return new Inferred(null, EnforcementResult.failed(null, asAuthZenException(e),
                    policy.getErrorStatus()));
        }
        switch (inference.getKind()) {
            case EVALUATE:
                return new Inferred(inference.getRequest().orElseThrow(), null);
            case NOT_ENFORCED:
                return new Inferred(null, EnforcementResult.notEnforced());
            case UNAUTHENTICATED:
                return new Inferred(null, EnforcementResult.unauthenticated());
            default:
                return new Inferred(null, EnforcementResult.refused(inference.getReason().orElseThrow()));
        }
    }

    private EnforcementResult decided(EvaluationRequest evaluation, EvaluationResponse response, Throwable error) {
        if (error == null) {
            return EnforcementResult.decided(evaluation, response);
        }
        return failed(evaluation, error);
    }

    private EnforcementResult failed(EvaluationRequest evaluation, Throwable error) {
        AuthZenException failure = asAuthZenException(error);
        if (!policy.isFailOpen()) {
            return EnforcementResult.failed(evaluation, failure, policy.getErrorStatus());
        }
        long count = failOpenWarnings.count();
        if (failOpenWarnings.allow()) {
            LOGGER.log(Level.WARNING, "Fail-open: let a request through without a decision of the PDP ("
                    + count + " so far): " + failure.getClass().getName());
        }
        return EnforcementResult.failedOpen(evaluation, failure);
    }

    private static AuthZenException asAuthZenException(Throwable error) {
        Throwable cause = (error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof AuthZenException authZenException) {
            return authZenException;
        }
        if (cause instanceof CancellationException) {
            return new AuthZenException("The call to the PDP was cancelled", cause);
        }
        return new AuthZenException("Enforcement failed", cause);
    }

    private EnforcementResult publish(RequestDescription request, long start, EnforcementResult result) {
        if (events.isActive()) {
            events.publish(event(request, start)
                    .outcome(result.getOutcome())
                    .reason(result.getReason().orElse(null))
                    .failure(result.getFailure().orElse(null))
                    .build());
        }
        return result;
    }

    private void publishCancelled(RequestDescription request, long start) {
        if (events.isActive()) {
            events.publish(event(request, start).build());
        }
    }

    private static EnforcementCompleted.Builder event(RequestDescription request, long start) {
        return EnforcementCompleted.builder()
                .method(request.getMethod())
                .routePattern(request.getRoutePattern())
                .duration(Duration.ofNanos(System.nanoTime() - start))
                .time(Instant.now());
    }

    @Override
    public String toString() {
        return "Enforcer{client=" + client + ", inferrer=" + inferrer + ", policy=" + policy + "}";
    }

    /** The evaluation request to send, or the result if there is none. */
    private static final class Inferred {

        private final EvaluationRequest evaluation;
        private final EnforcementResult result;

        Inferred(EvaluationRequest evaluation, EnforcementResult result) {
            this.evaluation = evaluation;
            this.result = result;
        }
    }

    /**
     * Builder for {@link Enforcer}. Not thread-safe.
     */
    public static final class Builder {

        private final AuthZenClient client;
        private RequestInferrer inferrer = RequestInferrer.defaults();
        private EnforcementPolicy policy = EnforcementPolicy.defaults();
        private final List<PepListener> listeners = new ArrayList<>();
        private Executor listenerExecutor;

        private Builder(AuthZenClient client) {
            this.client = client;
        }

        /**
         * Sets how evaluation requests are inferred. Defaults to {@link RequestInferrer#defaults()}.
         *
         * @param inferrer the inferrer
         * @return this builder
         */
        public Builder inferrer(RequestInferrer inferrer) {
            this.inferrer = Objects.requireNonNull(inferrer, "inferrer must not be null");
            return this;
        }

        /**
         * Sets how requests without a decision are answered. Defaults to
         * {@link EnforcementPolicy#defaults()}, which fails closed.
         *
         * @param policy the policy
         * @return this builder
         */
        public Builder policy(EnforcementPolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy must not be null");
            return this;
        }

        /**
         * Adds a listener for {@link PepListener#onEnforcementCompleted} events. It is delivered to
         * as the listeners of a client are; a listener registered with the client as well, such as
         * one that records metrics, gets both kinds of events.
         *
         * @param listener the listener
         * @return this builder
         */
        public Builder listener(PepListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
            return this;
        }

        /**
         * Sets the executor that delivers events to the listeners. By default each listener has a
         * daemon thread of its own; see {@link AuthZenClient.Builder#listenerExecutor(Executor)}.
         *
         * @param executor the executor
         * @return this builder
         */
        public Builder listenerExecutor(Executor executor) {
            this.listenerExecutor = Objects.requireNonNull(executor, "executor must not be null");
            return this;
        }

        /**
         * Builds the enforcer. With a fail-open policy, it logs a warning.
         *
         * @return the enforcer
         */
        public Enforcer build() {
            return new Enforcer(this);
        }
    }
}
