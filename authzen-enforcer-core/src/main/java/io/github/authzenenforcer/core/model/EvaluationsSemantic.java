package io.github.authzenenforcer.core.model;

/**
 * How the PDP executes the items of an Access Evaluations request ({@code options.evaluations_semantic}).
 */
public enum EvaluationsSemantic {

    /** Every item is evaluated and every decision is returned. The default. */
    EXECUTE_ALL("execute_all"),

    /** Evaluation stops at the first deny, like {@code &&}; later items are not returned. */
    DENY_ON_FIRST_DENY("deny_on_first_deny"),

    /** Evaluation stops at the first permit, like {@code ||}; later items are not returned. */
    PERMIT_ON_FIRST_PERMIT("permit_on_first_permit");

    private final String wireValue;

    EvaluationsSemantic(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Returns the value used on the wire.
     *
     * @return the value, such as {@code execute_all}
     */
    public String getWireValue() {
        return wireValue;
    }
}
