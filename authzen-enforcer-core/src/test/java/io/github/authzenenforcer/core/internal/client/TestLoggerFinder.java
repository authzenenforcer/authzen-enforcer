package io.github.authzenenforcer.core.internal.client;

import java.util.ResourceBundle;
import java.util.function.Supplier;

/**
 * Logging backend of the core tests, registered as a {@link System.LoggerFinder} service. It
 * discards log records, and while {@link #failing} is set, loggers of {@link MetadataResolver}
 * throw, as a broken logging backend of an application would.
 */
public final class TestLoggerFinder extends System.LoggerFinder {

    /** Makes the loggers of {@link MetadataResolver} throw. */
    static volatile boolean failing;

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
                if (failing && name.equals(MetadataResolver.class.getName())) {
                    throw new IllegalStateException("logging backend failed");
                }
            }
        };
    }
}
