package com.mediaworkspace.domain.share;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** SHARE-02: expiry bounds and revocation are decided against server time only. */
class ShareWindowTest {

    private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

    @Test
    @DisplayName("the window is closed at one minute and at seven days")
    void boundsAreInclusive() {
        assertThat(ShareWindow.isValid(NOW, NOW.plus(Duration.ofMinutes(1)))).isTrue();
        assertThat(ShareWindow.isValid(NOW, NOW.plus(Duration.ofDays(7)))).isTrue();
    }

    @Test
    @DisplayName("below one minute and above seven days are rejected")
    void outsideBoundsRejected() {
        assertThat(ShareWindow.isValid(NOW, NOW.plus(Duration.ofSeconds(59)))).isFalse();
        assertThat(ShareWindow.isValid(NOW, NOW.plus(Duration.ofDays(7)).plusSeconds(1))).isFalse();
        assertThat(ShareWindow.isValid(NOW, NOW)).isFalse();
        assertThat(ShareWindow.isValid(NOW, NOW.minus(Duration.ofMinutes(5)))).isFalse();
        assertThat(ShareWindow.isValid(NOW, null)).isFalse();
    }

    @Test
    @DisplayName("a share is active only while unrevoked and before its expiry")
    void activity() {
        Instant expiry = NOW.plus(Duration.ofHours(1));
        assertThat(ShareWindow.isActive(NOW, expiry, null)).isTrue();
        assertThat(ShareWindow.isActive(NOW, expiry, NOW.minusSeconds(1))).isFalse();
        assertThat(ShareWindow.isActive(expiry, expiry, null)).isFalse();
        assertThat(ShareWindow.isActive(NOW.plus(Duration.ofHours(2)), expiry, null)).isFalse();
    }
}
