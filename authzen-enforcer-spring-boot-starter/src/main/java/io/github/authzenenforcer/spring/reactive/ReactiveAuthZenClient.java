package io.github.authzenenforcer.spring.reactive;

import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.client.PaginationOptions;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.ActionSearchRequest;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.ResourceSearchRequest;
import io.github.authzenenforcer.core.model.SearchResponse;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.model.SubjectSearchRequest;
import io.github.authzenenforcer.spring.internal.ReactorObservations;

import org.springframework.util.ClassUtils;

import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/**
 * Calls the PDP from the code of a Spring WebFlux application, such as a handler that decides
 * about several resources at once, with the {@link AuthZenClient} of the application.
 *
 * <p>In Spring WebFlux, the observation of a request lives in the Reactor Context, not on a thread,
 * so a call started with the client's asynchronous methods would start a trace of its own. Every
 * call of this class starts with the request's observation in scope on its thread, so that its
 * span joins the trace of the request and the {@code traceparent} sent to the PDP carries that
 * trace, as for the starter's own enforcement. That needs {@code micrometer-observation} on the
 * class path, as with Spring Boot's tracing; without it, or without an observation, the calls are
 * made as they are.
 *
 * <p>Every method returns a publisher that calls the PDP when it is subscribed to, once per
 * subscription. Cancelling the subscription cancels the call. Failures are signalled as the
 * client's {@link io.github.authzenenforcer.core.AuthZenException}s: no decision is a deny, which
 * the application has to apply. As for the starter's enforcement, every call starts on Reactor's
 * bounded elastic scheduler, not on the event loop, so that a component of the client that blocks,
 * such as a credential provider that obtains a token, does not stall other requests; it completes
 * on one of the client's threads.
 *
 * <p>The starter configures a bean of this type in reactive web applications, unless the
 * application defines one. Instances are immutable and thread-safe.
 */
public final class ReactiveAuthZenClient {

    private static final String OBSERVATION = "io.micrometer.observation.Observation";

    private final AuthZenClient client;
    private final boolean observations;

    /**
     * Creates a reactive client that calls the PDP with the given client.
     *
     * @param client the client
     */
    public ReactiveAuthZenClient(AuthZenClient client) {
        // Visible to the starter, which links against it
        this(client, ClassUtils.isPresent(OBSERVATION, ReactiveAuthZenClient.class.getClassLoader()));
    }

    /**
     * Creates a reactive client that puts the request's observation in scope only if
     * {@code observations} is true: the auto-configuration passes whether the application and the
     * starter both see {@code micrometer-observation}, as for the enforcement.
     */
    ReactiveAuthZenClient(AuthZenClient client, boolean observations) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.observations = observations && ClassUtils.isPresent(OBSERVATION, ReactiveAuthZenClient.class.getClassLoader());
    }

    /**
     * Returns the client that makes the calls.
     *
     * @return the client
     */
    public AuthZenClient client() {
        return client;
    }

    /**
     * Asks the PDP for a single decision, as {@link AuthZenClient#evaluateAsync(EvaluationRequest)}.
     *
     * @param request the request
     * @return the decision
     */
    public Mono<EvaluationResponse> evaluate(EvaluationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return call(() -> client.evaluateAsync(request));
    }

    /**
     * Asks the PDP for several decisions in one request, as
     * {@link AuthZenClient#evaluationsAsync(EvaluationsRequest)}.
     *
     * @param request the request
     * @return the decisions
     */
    public Mono<EvaluationsResponse> evaluations(EvaluationsRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return call(() -> client.evaluationsAsync(request));
    }

    /**
     * Searches one page of subjects, as {@link AuthZenClient#searchSubjectsAsync(SubjectSearchRequest)}.
     *
     * @param request the request
     * @return the page
     */
    public Mono<SearchResponse<Subject>> searchSubjects(SubjectSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return call(() -> client.searchSubjectsAsync(request));
    }

    /**
     * Searches one page of resources, as
     * {@link AuthZenClient#searchResourcesAsync(ResourceSearchRequest)}.
     *
     * @param request the request
     * @return the page
     */
    public Mono<SearchResponse<Resource>> searchResources(ResourceSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return call(() -> client.searchResourcesAsync(request));
    }

    /**
     * Searches one page of actions, as {@link AuthZenClient#searchActionsAsync(ActionSearchRequest)}.
     *
     * @param request the request
     * @return the page
     */
    public Mono<SearchResponse<Action>> searchActions(ActionSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return call(() -> client.searchActionsAsync(request));
    }

    /**
     * Searches the subjects of all pages, as
     * {@link AuthZenClient#searchAllSubjectsAsync(SubjectSearchRequest)}. Every page goes under the
     * span of the request.
     *
     * @param request the request
     * @return the subjects
     */
    public Flux<Subject> searchAllSubjects(SubjectSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return all(() -> client.searchAllSubjectsAsync(request));
    }

    /**
     * Searches the subjects of all pages with the given limits, as
     * {@link AuthZenClient#searchAllSubjectsAsync(SubjectSearchRequest, PaginationOptions)}.
     *
     * @param request the request
     * @param options the limits of the pagination
     * @return the subjects
     */
    public Flux<Subject> searchAllSubjects(SubjectSearchRequest request, PaginationOptions options) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(options, "options must not be null");
        return all(() -> client.searchAllSubjectsAsync(request, options));
    }

    /**
     * Searches the resources of all pages, as
     * {@link AuthZenClient#searchAllResourcesAsync(ResourceSearchRequest)}. Every page goes under the
     * span of the request.
     *
     * @param request the request
     * @return the resources
     */
    public Flux<Resource> searchAllResources(ResourceSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return all(() -> client.searchAllResourcesAsync(request));
    }

    /**
     * Searches the resources of all pages with the given limits, as
     * {@link AuthZenClient#searchAllResourcesAsync(ResourceSearchRequest, PaginationOptions)}.
     *
     * @param request the request
     * @param options the limits of the pagination
     * @return the resources
     */
    public Flux<Resource> searchAllResources(ResourceSearchRequest request, PaginationOptions options) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(options, "options must not be null");
        return all(() -> client.searchAllResourcesAsync(request, options));
    }

    /**
     * Searches the actions of all pages, as
     * {@link AuthZenClient#searchAllActionsAsync(ActionSearchRequest)}. Every page goes under the
     * span of the request.
     *
     * @param request the request
     * @return the actions
     */
    public Flux<Action> searchAllActions(ActionSearchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return all(() -> client.searchAllActionsAsync(request));
    }

    /**
     * Searches the actions of all pages with the given limits, as
     * {@link AuthZenClient#searchAllActionsAsync(ActionSearchRequest, PaginationOptions)}.
     *
     * @param request the request
     * @param options the limits of the pagination
     * @return the actions
     */
    public Flux<Action> searchAllActions(ActionSearchRequest request, PaginationOptions options) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(options, "options must not be null");
        return all(() -> client.searchAllActionsAsync(request, options));
    }

    @Override
    public String toString() {
        return "ReactiveAuthZenClient{client=" + client + "}";
    }

    // Cancelling the subscription cancels the stage, and with it the call. The Reactor Context
    // reaches the scheduler's thread with the subscription
    private <T> Mono<T> call(Supplier<CompletionStage<T>> call) {
        return Mono.deferContextual(context -> Mono.fromCompletionStage(start(context, call)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // The search starts, and takes the span of its pages from the thread, when it is subscribed to
    private <T> Flux<T> all(Supplier<Flow.Publisher<T>> search) {
        return Flux.deferContextual(context -> JdkFlowAdapter.flowPublisherToFlux(start(context, search)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private <T> T start(ContextView context, Supplier<T> call) {
        if (!observations) {
            return call.get();
        }
        return ReactorObservations.call(context, call);
    }
}
