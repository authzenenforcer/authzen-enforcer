package io.github.authzenenforcer.spring.internal;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;

import reactor.util.context.ContextView;

import java.util.ServiceConfigurationError;
import java.util.function.Supplier;

/**
 * Puts the observation of a Spring WebFlux request on the thread while a call to the PDP starts.
 *
 * <p>Spring WebFlux keeps the observation of a request in the Reactor Context, not on a thread, so a
 * tracer that starts its span from the thread's current context would start a new trace. Only the
 * observation is restored, and only for the duration of the given call; other thread locals stay as
 * they are. Loaded only when {@code io.micrometer:context-propagation} and
 * {@code micrometer-observation} are on the class path.
 */
final class ReactorObservations {

    private static final System.Logger LOGGER = System.getLogger(ReactorObservations.class.getName());

    private ReactorObservations() {
    }

    /**
     * Runs the call with the observation of the Reactor Context on the current thread. If the
     * observation cannot be restored, the call runs without it: tracing never changes a decision.
     * That includes errors, such as a {@code ServiceConfigurationError} from a broken
     * {@code ThreadLocalAccessor} registration, which Reactor would otherwise treat as fatal and
     * leave the request unanswered.
     *
     * @param context the Reactor Context of the request
     * @param call    the call to run
     * @param <T>     the type of the result
     * @return the result of the call
     */
    static <T> T call(ContextView context, Supplier<T> call) {
        ContextSnapshot.Scope scope;
        try {
            scope = Snapshots.FACTORY.setThreadLocalsFrom(context, ObservationThreadLocalAccessor.KEY);
        } catch (RuntimeException | LinkageError | ServiceConfigurationError e) {
            LOGGER.log(System.Logger.Level.DEBUG, () -> "Restoring the observation of the request failed with "
                    + e.getClass().getName() + "; the call to the PDP starts without it");
            return call.get();
        }
        try {
            return call.get();
        } finally {
            try {
                scope.close();
            } catch (RuntimeException | LinkageError | ServiceConfigurationError e) {
                LOGGER.log(System.Logger.Level.DEBUG, () -> "Closing the observation scope of the request failed with "
                        + e.getClass().getName());
            }
        }
    }

    // Created on first use, so that a failure to create it fails only the restoration, every time
    private static final class Snapshots {

        static final ContextSnapshotFactory FACTORY = ContextSnapshotFactory.builder().build();

        private Snapshots() {
        }
    }
}
