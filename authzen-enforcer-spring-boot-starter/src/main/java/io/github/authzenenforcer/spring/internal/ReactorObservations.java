package io.github.authzenenforcer.spring.internal;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;

import reactor.util.context.ContextView;

import java.util.function.Supplier;

/**
 * Puts the observation of a Spring WebFlux request on the thread while a call to the PDP starts.
 *
 * <p>Spring WebFlux keeps the observation of a request in the Reactor Context, under
 * {@link ObservationThreadLocalAccessor#KEY}, not on a thread, so a tracer that starts its span
 * from the thread's current context would start a new trace. The observation's scope is opened for
 * the duration of the given call only, on the calling thread, and the thread is left as it was
 * found, also when opening or closing the scope fails. Loaded only when
 * {@code micrometer-observation} is on the class path.
 */
final class ReactorObservations {

    private static final System.Logger LOGGER = System.getLogger(ReactorObservations.class.getName());

    private ReactorObservations() {
    }

    /**
     * Runs the call with the observation of the Reactor Context in scope on the current thread.
     * Tracing never changes a decision: if the scope cannot be opened, the call runs without it,
     * and a failure to close it does not change the call's result. Errors of the observation
     * handlers are handled the same way, because Reactor would treat some of them as fatal and
     * leave the request unanswered; only a {@link VirtualMachineError} is rethrown.
     *
     * @param context the Reactor Context of the request
     * @param call    the call to run
     * @param <T>     the type of the result
     * @return the result of the call
     */
    static <T> T call(ContextView context, Supplier<T> call) {
        Object value = context.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
        if (!(value instanceof Observation observation) || observation.isNoop()) {
            return call.get();
        }
        ObservationRegistry registry = null;
        Observation.Scope previous = null;
        Observation.Scope scope;
        try {
            registry = observation.getObservationRegistry();
            previous = registry.getCurrentObservationScope();
            scope = observation.openScope();
        } catch (Throwable e) { // NOSONAR: handler errors must not leave the request unanswered
            rethrowIfFatal(e);
            LOGGER.log(System.Logger.Level.DEBUG, () -> "Opening the observation scope of the request failed with "
                    + e.getClass().getName() + "; the call to the PDP starts without it");
            // The scope may be current already when a handler fails after it was set
            leave(registry, previous);
            return call.get();
        }
        try {
            return call.get();
        } finally {
            close(scope, registry, previous);
        }
    }

    private static void close(Observation.Scope scope, ObservationRegistry registry, Observation.Scope previous) {
        try {
            scope.close();
        } catch (Throwable e) { // NOSONAR: handler errors must not change the result of a started call
            rethrowIfFatal(e);
            LOGGER.log(System.Logger.Level.DEBUG, () -> "Closing the observation scope of the request failed with "
                    + e.getClass().getName());
            leave(registry, previous);
        }
    }

    // Leaves the thread with the scope that was current before, so that a later task on this
    // worker does not start under this request's observation
    private static void leave(ObservationRegistry registry, Observation.Scope previous) {
        if (registry == null) {
            return;
        }
        try {
            Observation.Scope current = registry.getCurrentObservationScope();
            if (current == null || current == previous) {
                return;
            }
            if (previous == null) {
                current.reset();
            } else {
                registry.setCurrentObservationScope(previous);
            }
        } catch (Throwable e) { // NOSONAR: as above
            rethrowIfFatal(e);
            LOGGER.log(System.Logger.Level.DEBUG, () -> "Resetting the observation scope of the request failed with "
                    + e.getClass().getName());
            // A handler that failed again stopped the reset half-way: at least the registry forgets the scope
            registry.setCurrentObservationScope(previous);
        }
    }

    private static void rethrowIfFatal(Throwable e) {
        if (e instanceof VirtualMachineError fatal) {
            throw fatal;
        }
    }
}
