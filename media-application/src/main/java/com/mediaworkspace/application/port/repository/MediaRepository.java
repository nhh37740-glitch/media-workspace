package com.mediaworkspace.application.port.repository;

import com.mediaworkspace.application.model.MediaQuery;
import com.mediaworkspace.application.model.MediaRecord;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Media rows. Every read path filters {@code deleted_at IS NOT NULL}. */
public interface MediaRepository {

    void insert(MediaRecord media);

    /** Reads a media that is not deleted; a deleted media is reported as absent. */
    Optional<MediaRecord> findVisible(String mediaId);

    /**
     * Locks a media row for a state change.
     *
     * <p>Lock order is fixed system-wide: capacity, workspace, upload, media, task, attempt. A
     * caller that already holds the task lock must not call this; it locks the media first.
     */
    Optional<MediaRecord> lockVisible(String mediaId);

    List<MediaRecord> search(MediaQuery query);

    long count(MediaQuery query);

    /**
     * Renames a media under optimistic locking.
     *
     * @return {@code false} when the version did not match
     */
    boolean rename(String mediaId, String title, long expectedVersion);

    /**
     * Shadows a media.
     *
     * <p>The deletion timestamp comes from the database clock, so it is ordered correctly against
     * every other timestamp in the schema.
     *
     * @return whether this call performed the deletion
     */
    boolean markDeleted(String mediaId);

    /** Reads a media even when it is deleted, for projections that must still resolve its space. */
    Optional<MediaRecord> findAnyIncludingDeleted(String mediaId);

    /** Whether any row, deleted or not, still points at this storage key. */
    boolean isStorageKeyReferenced(String storageKey);

    /** Media rows deleted at least {@code lookback} ago, whose files the collector may consider. */
    List<MediaRecord> findDeletedBefore(Duration lookback, int limit);
}
