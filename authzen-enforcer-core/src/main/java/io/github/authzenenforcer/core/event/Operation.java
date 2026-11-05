package io.github.authzenenforcer.core.event;

import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.ActionSearchRequest;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.ResourceSearchRequest;
import io.github.authzenenforcer.core.model.SearchResponse;
import io.github.authzenenforcer.core.model.Subject;
import io.github.authzenenforcer.core.model.SubjectSearchRequest;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A kind of call to the PDP, typed by its request and response.
 *
 * <p>The constants let a listener read the request and response of a {@link CallCompleted} event
 * without casts:
 * <pre>{@code
 * public void onCallCompleted(CallCompleted<?, ?> event) {
 *     event.as(Operation.EVALUATION).ifPresent(evaluation -> {
 *         EvaluationRequest request = evaluation.getRequest();
 *         ...
 *     });
 * }
 * }</pre>
 *
 * <p>Instances are immutable and thread-safe; the constants are the only instances.
 *
 * @param <Q> the type of the request
 * @param <R> the type of the response
 */
public final class Operation<Q, R> {

    /** An Access Evaluation. */
    public static final Operation<EvaluationRequest, EvaluationResponse> EVALUATION = new Operation<>("evaluation",
            Masking::evaluationRequest, Masking::evaluationResponse,
            response -> response.getDecision() ? Outcome.PERMIT : Outcome.DENY);

    /** An Access Evaluations (batch) request. */
    public static final Operation<EvaluationsRequest, EvaluationsResponse> EVALUATIONS = new Operation<>(
            "evaluations", Masking::evaluationsRequest, Masking::evaluationsResponse, response -> Outcome.COMPLETED);

    /** One page of a Subject Search. */
    public static final Operation<SubjectSearchRequest, SearchResponse<Subject>> SUBJECT_SEARCH = new Operation<>(
            "subject_search", Masking::subjectSearchRequest,
            (response, content) -> Masking.searchResponse(response, content, Masking::subject),
            response -> Outcome.COMPLETED);

    /** One page of a Resource Search. */
    public static final Operation<ResourceSearchRequest, SearchResponse<Resource>> RESOURCE_SEARCH = new Operation<>(
            "resource_search", Masking::resourceSearchRequest,
            (response, content) -> Masking.searchResponse(response, content, Masking::resource),
            response -> Outcome.COMPLETED);

    /** One page of an Action Search. */
    public static final Operation<ActionSearchRequest, SearchResponse<Action>> ACTION_SEARCH = new Operation<>(
            "action_search", Masking::actionSearchRequest,
            (response, content) -> Masking.searchResponse(response, content, Masking::action),
            response -> Outcome.COMPLETED);

    /**
     * The request for the PDP metadata. It only appears in {@link AttemptCompleted} events; the
     * result of a discovery is reported as a {@link MetadataRefresh}.
     */
    public static final Operation<Void, PdpMetadata> METADATA = new Operation<>("metadata",
            (request, content) -> request, (response, content) -> response, response -> Outcome.COMPLETED);

    private static final List<Operation<?, ?>> VALUES = List.of(EVALUATION, EVALUATIONS, SUBJECT_SEARCH,
            RESOURCE_SEARCH, ACTION_SEARCH, METADATA);

    private final String name;
    private final BiFunction<Q, EventContent, Q> requestMasking;
    private final BiFunction<R, EventContent, R> responseMasking;
    private final Function<R, Outcome> outcome;

    private Operation(String name, BiFunction<Q, EventContent, Q> requestMasking,
            BiFunction<R, EventContent, R> responseMasking, Function<R, Outcome> outcome) {
        this.name = name;
        this.requestMasking = requestMasking;
        this.responseMasking = responseMasking;
        this.outcome = outcome;
    }

    /**
     * Returns all operations.
     *
     * @return the constants of this class, in declaration order
     */
    public static List<Operation<?, ?>> values() {
        return VALUES;
    }

    /**
     * Returns the name of the operation, such as {@code evaluation} or {@code subject_search}; it
     * suits metric tags and span names.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    Q maskRequest(Q request, EventContent content) {
        return requestMasking.apply(request, content);
    }

    R maskResponse(R response, EventContent content) {
        return responseMasking.apply(response, content);
    }

    Outcome outcome(R response) {
        return outcome.apply(response);
    }

    @Override
    public String toString() {
        return name;
    }
}
