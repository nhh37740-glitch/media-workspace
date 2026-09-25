package com.mediaworkspace.persistence.mapper;

import org.apache.ibatis.annotations.Param;

/** SQL for {@code audit_event}. */
public interface AuditMapper {

    /**
     * Appends a projection entry.
     *
     * <p>{@code INSERT IGNORE} plus the unique key on {@code source_event_id} is what keeps the
     * projection single when the same result event is consumed twice.
     *
     * @return 1 when a row was inserted, 0 when this event was already projected
     */
    int appendFromEvent(@Param("workspaceId") String workspaceId, @Param("actorId") String actorId,
                        @Param("action") String action, @Param("resourceId") String resourceId,
                        @Param("requestId") String requestId, @Param("detailJson") String detailJson,
                        @Param("sourceEventId") String sourceEventId);
}
