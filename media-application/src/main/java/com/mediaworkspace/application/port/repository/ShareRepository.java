package com.mediaworkspace.application.port.repository;

import com.mediaworkspace.application.model.ShareLinkRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Share links. Only token hashes are stored, so a raw token cannot be recovered from a row. */
public interface ShareRepository {

    void insert(ShareLinkRecord link);

    Optional<ShareLinkRecord> findById(String shareId);

    Optional<ShareLinkRecord> findByTokenHash(String tokenHash);

    List<ShareLinkRecord> listByMedia(String mediaId, int limit);

    /**
     * Counts shares of a media that are neither revoked nor expired.
     *
     * <p>Checked while the media row lock is held so two concurrent creations cannot both observe
     * a free slot.
     */
    long countActiveForMedia(String mediaId, Instant now);

    /**
     * Revokes a share.
     *
     * @return {@code true} when this call performed the revocation, {@code false} when the share
     *         was already revoked; a repeated revoke is not an error
     */
    boolean revoke(String shareId, Instant revokedAt);
}
