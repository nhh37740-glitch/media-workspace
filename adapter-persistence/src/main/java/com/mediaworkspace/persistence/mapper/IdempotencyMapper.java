package com.mediaworkspace.persistence.mapper;

import com.mediaworkspace.application.model.IdempotencyRecord;
import org.apache.ibatis.annotations.Param;

/** SQL for {@code idempotency_record}. */
public interface IdempotencyMapper {

    IdempotencyRecord find(@Param("userId") String userId, @Param("route") String route,
                           @Param("resourceId") String resourceId, @Param("keyHash") String keyHash);

    /**
     * Stores a response.
     *
     * <p>The composite primary key means a concurrent duplicate surfaces as a duplicate-key
     * violation rather than as two stored rows; the caller then re-reads and returns the winner's
     * response instead of repeating the business operation.
     */
    int insert(@Param("r") IdempotencyRecord record);
}
