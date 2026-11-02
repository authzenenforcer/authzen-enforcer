package io.github.authzenenforcer.core.internal.client;

import java.util.Arrays;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Logging backend of the core tests, registered as a {@link System.LoggerFinder} service. It
 * discards log records, and the loggers named with {@link #fail(Class...)} throw, as a broken
 * logging backend of an application would.
 */
public final class TestLoggerFinder extends System.LoggerFinder {

    private static volatile Set<String> failing = Set.of();
    private static volatile boolean errors;

    /**
     * Makes the loggers of the given classes throw until {@link #reset()}.
     *
     * @param classes the classes whose loggers fail
     */
    public static void fail(Class<?>... classes) {
        errors = false;
        failing = Arrays.stream(classes).map(Class::getName).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Makes the loggers of the given classes throw an {@link Error} until {@link #reset()}.
     *
     * @param classes the classes whose loggers fail
     */
    public static void failWithError(Class<?>... classes) {
        fail(classes);
        errors = true;
    }

    /** Lets every logger work again. */
    public static void reset() {
        failing = Set.of();
    }

    @Override
    public System.Logger getLogger(String name, Module module) {
        return new System.Logger() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean isLoggable(Level level) {
                return true;
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
                check();
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String format, Object... params) {
                check();
            }

            @Override
            public void log(Level level, Supplier<String> supplier) {
                check();
            }

            private void check() {
                if (failing.contains(name)) {
                    if (errors) {
                        throw new AssertionError("logging backend failed");
                    }
                    throw new IllegalStateException("logging backend failed");
                }
            }
        };
    }
}
