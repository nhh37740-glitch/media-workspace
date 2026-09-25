package com.mediaworkspace.application.port.repository;

import com.mediaworkspace.application.model.ShareSession;

import java.time.Instant;
import java.util.Optional;

/**
 * Share-derived browser sessions.
 *
 * <p>Stored server-side so that revoking a share takes effect on the next request: the session row
 * is not self-sufficient, every read re-checks the share it came from.
 */
public interface ShareSessionRepository {

    void insert(ShareSession session);

    Optional<ShareSession> findById(String sessionId);

    /** Removes sessions that have expired, so the table does not grow without bound. */
    int deleteExpired(Instant now, int limit);
}
