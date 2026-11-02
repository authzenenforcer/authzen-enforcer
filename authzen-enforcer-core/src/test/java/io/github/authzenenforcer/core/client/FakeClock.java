package io.github.authzenenforcer.core.client;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** A monotonic nanosecond clock that only moves when a test advances it. */
final class FakeClock implements LongSupplier {

    // Starts near the overflow point, so that the cache arithmetic must be wrap-safe
    private final AtomicLong nanos = new AtomicLong(Long.MAX_VALUE - Duration.ofMinutes(30).toNanos());

    @Override
    public long getAsLong() {
        return nanos.get();
    }

    void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }
}
