package io.github.authzenenforcer.core.internal.http;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.net.ssl.SSLException;

/**
 * Decides whether a failed exchange with the PDP is repeated, and after which delay.
 *
 * <p>AuthZEN requests have no side effects, so repeating them is safe. Only failures that are
 * likely to be transient are retried:
 * <ul>
 *   <li>connection failures, such as a refused connection, a connect timeout or a pooled
 *       connection the server has closed;</li>
 *   <li>HTTP {@code 429}, {@code 502}, {@code 503} and {@code 504}.</li>
 * </ul>
 * Request timeouts are not retried, since that would multiply the latency of a slow PDP; neither are
 * TLS errors, other statuses or invalid responses, which would fail the same way again.
 *
 * <p>Delays grow exponentially with full jitter. A {@code Retry-After} header on {@code 429} or
 * {@code 503} is honoured, unless it asks for more than {@link #MAX_DELAY}, in which case the request
 * is not retried. No retry starts once the budget, measured from the first attempt, is used up.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class RetryPolicy {

    /** Upper bound of the first delay. */
    static final Duration BASE_DELAY = Duration.ofMillis(100);

    /** Upper bound of any delay, including one requested with {@code Retry-After}. */
    static final Duration MAX_DELAY = Duration.ofSeconds(2);

    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(429, 502, 503, 504);
    private static final Set<Integer> RETRY_AFTER_STATUSES = Set.of(429, 503);

    private final int maxRetries;
    private final Duration budget;
    private final Clock clock;

    /**
     * Creates a policy.
     *
     * @param maxRetries how often a request may be repeated, {@code 0} to disable retries
     * @param budget     time after the first attempt within which retries may start
     * @param clock      clock for {@code Retry-After} dates
     */
    RetryPolicy(int maxRetries, Duration budget, Clock clock) {
        this.maxRetries = maxRetries;
        this.budget = budget;
        this.clock = clock;
    }

    /**
     * Returns the delay before repeating a request that failed without a response.
     *
     * @param retries number of retries already made
     * @param elapsed time since the first attempt started
     * @param error   the failure
     * @return the delay, or empty if the request must not be repeated
     */
    Optional<Duration> afterError(int retries, Duration elapsed, Throwable error) {
        if (!isTransient(error)) {
            return Optional.empty();
        }
        return within(retries, elapsed, backoff(retries));
    }

    /**
     * Returns the delay before repeating a request that received an error response.
     *
     * @param retries  number of retries already made
     * @param elapsed  time since the first attempt started
     * @param response the response
     * @return the delay, or empty if the request must not be repeated
     */
    Optional<Duration> afterResponse(int retries, Duration elapsed, HttpResponse<?> response) {
        int status = response.statusCode();
        if (!TRANSIENT_STATUSES.contains(status)) {
            return Optional.empty();
        }
        Duration delay = backoff(retries);
        if (RETRY_AFTER_STATUSES.contains(status)) {
            Optional<Duration> requested = response.headers().firstValue("Retry-After").flatMap(this::parseRetryAfter);
            if (requested.isPresent()) {
                if (requested.get().compareTo(MAX_DELAY) > 0) {
                    return Optional.empty();
                }
                delay = requested.get();
            }
        }
        return within(retries, elapsed, delay);
    }

    static boolean isTransient(Throwable error) {
        if (error instanceof HttpConnectTimeoutException) {
            return true;
        }
        // Checked after the connect timeout, which is a subclass
        if (error instanceof HttpTimeoutException || error instanceof SSLException
                || error instanceof LimitedBodyHandler.BodyTooLargeException) {
            return false;
        }
        return error instanceof IOException;
    }

    private Optional<Duration> within(int retries, Duration elapsed, Duration delay) {
        if (retries >= maxRetries || elapsed.plus(delay).compareTo(budget) >= 0) {
            return Optional.empty();
        }
        return Optional.of(delay);
    }

    // Full jitter: a random delay between zero and the exponentially growing cap
    private static Duration backoff(int retries) {
        long cap = Math.min(MAX_DELAY.toMillis(), BASE_DELAY.toMillis() << Math.min(retries, 16));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(cap + 1));
    }

    private Optional<Duration> parseRetryAfter(String value) {
        String trimmed = value.trim();
        if (!trimmed.isEmpty() && trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(trimmed)));
            } catch (NumberFormatException e) {
                return Optional.of(Duration.ofSeconds(Long.MAX_VALUE));
            }
        }
        try {
            Duration until = Duration.between(clock.instant(), ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME));
            return Optional.of(until.isNegative() ? Duration.ZERO : until);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
