package com.mediaworkspace.persistence.mapper;

import com.mediaworkspace.application.model.MediaRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** SQL for {@code media}. */
public interface MediaMapper {

    int insert(@Param("m") MediaRecord media);

    MediaRecord findVisible(@Param("mediaId") String mediaId);

    /** Takes the media lock; callers must already hold the capacity and workspace locks. */
    MediaRecord lockVisible(@Param("mediaId") String mediaId);

    /**
     * Searches by title inside one workspace.
     *
     * @param escapedPattern the search text with LIKE metacharacters escaped, or {@code null} for
     *                       every row in the workspace
     */
    List<MediaRecord> search(@Param("workspaceId") String workspaceId,
                             @Param("escaped") String escapedPattern,
                             @Param("limit") int limit, @Param("offset") int offset);

    long count(@Param("workspaceId") String workspaceId,
               @Param("escaped") String escapedPattern);

    int rename(@Param("mediaId") String mediaId, @Param("title") String title,
               @Param("expectedVersion") long expectedVersion);

    int markDeleted(@Param("mediaId") String mediaId);

    MediaRecord findAnyIncludingDeleted(@Param("mediaId") String mediaId);

    int countStorageKeyReferences(@Param("storageKey") String storageKey);

    List<MediaRecord> findDeletedBefore(@Param("lookbackSeconds") long lookbackSeconds,
                                        @Param("limit") int limit);
}
