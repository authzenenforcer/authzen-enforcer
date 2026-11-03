package io.github.authzenenforcer.core.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The answer to an Access Evaluations request: one decision per item, in request order.
 *
 * <p>With {@link EvaluationsSemantic#DENY_ON_FIRST_DENY} or
 * {@link EvaluationsSemantic#PERMIT_ON_FIRST_PERMIT} the PDP stops at the deciding item, so the
 * response may hold fewer decisions than the request had items. A missing decision is a deny: the
 * helpers here never treat it as a permit.
 *
 * <p>An item the PDP could not evaluate is a deny, usually with the reason in its context.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class EvaluationsResponse {

    private final List<EvaluationResponse> evaluations;
    private final int requested;

    private EvaluationsResponse(List<EvaluationResponse> evaluations, int requested) {
        this.evaluations = List.copyOf(evaluations);
        this.requested = requested;
    }

    /**
     * Creates a response.
     *
     * @param evaluations the decisions, in request order
     * @param requested   how many items the request had, at least the number of decisions
     * @return the response
     * @throws IllegalArgumentException if there are more decisions than requested items
     */
    public static EvaluationsResponse of(List<EvaluationResponse> evaluations, int requested) {
        Objects.requireNonNull(evaluations, "evaluations");
        if (requested < evaluations.size()) {
            throw new IllegalArgumentException("more decisions than requested items");
        }
        return new EvaluationsResponse(evaluations, requested);
    }

    /**
     * Returns the decisions the PDP returned.
     *
     * @return an unmodifiable list in request order; shorter than the request if the PDP stopped early
     */
    public List<EvaluationResponse> getEvaluations() {
        return evaluations;
    }

    /**
     * Returns how many items the request had.
     *
     * @return the number of requested decisions
     */
    public int getRequested() {
        return requested;
    }

    /**
     * Returns the decision for an item.
     *
     * @param index the item's position in the request
     * @return the decision, or empty if the PDP stopped before this item
     * @throws IndexOutOfBoundsException if the request had no such item
     */
    public Optional<EvaluationResponse> get(int index) {
        Objects.checkIndex(index, requested);
        return index < evaluations.size() ? Optional.of(evaluations.get(index)) : Optional.empty();
    }

    /**
     * Tells whether an item is permitted.
     *
     * @param index the item's position in the request
     * @return {@code true} only if the PDP returned a permit for it
     * @throws IndexOutOfBoundsException if the request had no such item
     */
    public boolean isPermitted(int index) {
        return get(index).map(EvaluationResponse::getDecision).orElse(false);
    }

    /**
     * Tells whether every item is permitted.
     *
     * @return {@code true} only if the PDP returned a permit for each requested item
     */
    public boolean allPermitted() {
        return evaluations.size() == requested && evaluations.stream().allMatch(EvaluationResponse::getDecision);
    }

    /**
     * Tells whether at least one item is permitted.
     *
     * @return {@code true} if the PDP returned a permit for any item
     */
    public boolean anyPermitted() {
        return evaluations.stream().anyMatch(EvaluationResponse::getDecision);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EvaluationsResponse other)) {
            return false;
        }
        return requested == other.requested && evaluations.equals(other.evaluations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(evaluations, requested);
    }

    @Override
    public String toString() {
        return "EvaluationsResponse{evaluations=" + evaluations + ", requested=" + requested + "}";
    }
}
