package com.mediaworkspace.application.model;

import java.time.Instant;

/**
 * A share link. Only the hash of the token is stored, so the raw secret exists only in the
 * creation response.
 *
 * @param tokenHash SHA-256 of the URL-safe token
 */
public record ShareLinkRecord(
        String id,
        String mediaId,
        String creatorId,
        String tokenHash,
        Instant expiresAt,
        Instant revokedAt,
        Instant createdAt) {

    public boolean isActive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }
}
