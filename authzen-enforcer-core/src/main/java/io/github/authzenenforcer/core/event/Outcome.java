package io.github.authzenenforcer.core.event;

/**
 * How a call to the PDP ended.
 */
public enum Outcome {

    /** An Access Evaluation that the PDP permitted. */
    PERMIT,

    /** An Access Evaluation that the PDP denied. */
    DENY,

    /** A batch evaluation or a search page that the PDP answered. */
    COMPLETED,

    /**
     * A call that failed, for example because the PDP could not be reached, rejected the
     * credentials or sent an invalid answer. Callers deny in that case, so a failed evaluation is a
     * deny the PDP may never have seen.
     */
    FAILED
}
