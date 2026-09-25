package com.mediaworkspace.domain.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** JOB-04: the retry delays are two seconds then four, plus bounded jitter. */
class BackoffPolicyTest {

    @Test
    @DisplayName("no failure means no delay")
    void zeroAttempts() {
        assertThat(new BackoffPolicy(new Random(1)).delayAfter(0)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("the first retry waits two seconds and the second four, each with 0..250 ms jitter")
    void delayLadder() {
        BackoffPolicy policy = new BackoffPolicy(new Random(42));
        for (int i = 0; i < 200; i++) {
            Duration first = policy.delayAfter(1);
            assertThat(first).isBetween(Duration.ofMillis(2000), Duration.ofMillis(2250));
            Duration second = policy.delayAfter(2);
            assertThat(second).isBetween(Duration.ofMillis(4000), Duration.ofMillis(4250));
        }
    }

    @Test
    @DisplayName("jitter actually varies, so simultaneous failures do not align")
    void jitterVaries() {
        BackoffPolicy policy = new BackoffPolicy(new Random(7));
        boolean sawDifference = false;
        Duration previous = policy.delayAfter(1);
        for (int i = 0; i < 50 && !sawDifference; i++) {
            Duration next = policy.delayAfter(1);
            sawDifference = !next.equals(previous);
            previous = next;
        }
        assertThat(sawDifference).isTrue();
    }
}
