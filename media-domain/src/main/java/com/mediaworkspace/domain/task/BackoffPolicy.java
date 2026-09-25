package com.mediaworkspace.domain.task;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Delay before a retryable failure is claimed again: 2 seconds after the first failure, 4 seconds
 * after the second, plus a jitter of 0..250 ms so simultaneous failures do not align.
 */
public final class BackoffPolicy {

    /** Jitter is added to the base delay to spread out simultaneous retries. */
    public static final long MAX_JITTER_MILLIS = 250;

    private static final long FIRST_RETRY_MILLIS = 2_000;
    private static final long SECOND_RETRY_MILLIS = 4_000;

    private final RandomGenerator random;

    public BackoffPolicy(RandomGenerator random) {
        this.random = random;
    }

    /**
     * Delay before the next attempt.
     *
     * @param attemptsConsumed executions started in this generation so far
     * @return base delay for the failure ordinal plus jitter; zero when no attempt has been made
     */
    public Duration delayAfter(int attemptsConsumed) {
        if (attemptsConsumed <= 0) {
            return Duration.ZERO;
        }
        long base = attemptsConsumed == 1 ? FIRST_RETRY_MILLIS
                : attemptsConsumed == 2 ? SECOND_RETRY_MILLIS
                : SECOND_RETRY_MILLIS;
        return Duration.ofMillis(base + random.nextLong(MAX_JITTER_MILLIS + 1));
    }
}
