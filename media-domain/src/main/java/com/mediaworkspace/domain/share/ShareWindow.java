package com.mediaworkspace.domain.share;

import java.time.Duration;
import java.time.Instant;

/**
 * Validity window of a share link: at least one minute and at most seven days from the moment the
 * request is validated on the server.
 */
public final class ShareWindow {

    public static final Duration MINIMUM = Duration.ofMinutes(1);
    public static final Duration MAXIMUM = Duration.ofDays(7);

    private ShareWindow() {
    }

    /**
     * Whether {@code expiresAt} is inside the allowed window relative to {@code now}.
     *
     * <p>A client clock is never trusted: only the server's {@code now} is used.
     */
    public static boolean isValid(Instant now, Instant expiresAt) {
        if (expiresAt == null) {
            return false;
        }
        Duration ahead = Duration.between(now, expiresAt);
        return !ahead.minus(MINIMUM).isNegative() && ahead.compareTo(MAXIMUM) <= 0;
    }

    /** Whether a share is usable at {@code now}: not expired. */
    public static boolean isActive(Instant now, Instant expiresAt, Instant revokedAt) {
        return expiresAt != null && revokedAt == null && now.isBefore(expiresAt);
    }
}
