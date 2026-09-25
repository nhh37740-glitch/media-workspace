package com.mediaworkspace.persistence.mapper;

import com.mediaworkspace.application.model.ShareLinkRecord;
import com.mediaworkspace.application.model.ShareSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** SQL for {@code share_link} and {@code share_session}. */
public interface ShareMapper {

    int insertLink(@Param("l") ShareLinkRecord link);

    ShareLinkRecord findLinkById(@Param("shareId") String shareId);

    /** Lookup by token hash. The raw token is never stored, so it cannot be recovered from a row. */
    ShareLinkRecord findLinkByTokenHash(@Param("tokenHash") String tokenHash);

    List<ShareLinkRecord> listByMedia(@Param("mediaId") String mediaId, @Param("limit") int limit);

    /** Active means neither revoked nor expired, evaluated by the database clock. */
    long countActiveForMedia(@Param("mediaId") String mediaId);

    int revoke(@Param("shareId") String shareId);

    int insertSession(@Param("s") ShareSession session);

    ShareSession findSessionById(@Param("sessionId") String sessionId);

    int deleteExpiredSessions(@Param("limit") int limit);
}
