package com.mediaworkspace.persistence.mapper;

import com.mediaworkspace.application.model.Membership;
import com.mediaworkspace.application.model.Workspace;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** SQL for {@code workspace} and {@code workspace_member}. */
public interface WorkspaceMapper {

    int insert(@Param("id") String id, @Param("name") String name, @Param("ownerId") String ownerId,
               @Param("quotaBytes") long quotaBytes);

    /**
     * Reads a workspace with the caller's role.
     *
     * <p>A left join, so a non-member still yields the row with a null role; callers that must not
     * reveal existence use the guarded query in the adapter instead.
     */
    Workspace findById(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

    List<Workspace> listForUser(@Param("userId") String userId);

    /**
     * Locks the workspace row for a quota decision.
     *
     * <p>This is the serialization point for reservations: two concurrent uploads block here
     * instead of both reading the same free balance.
     */
    Workspace lockForReservation(@Param("workspaceId") String workspaceId);

    int addReservedBytes(@Param("workspaceId") String workspaceId, @Param("delta") long delta);

    int releaseReservedBytes(@Param("workspaceId") String workspaceId, @Param("delta") long delta);

    int moveReservedToUsed(@Param("workspaceId") String workspaceId, @Param("bytes") long bytes);

    int releaseUsedBytes(@Param("workspaceId") String workspaceId, @Param("delta") long delta);

    String roleOf(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

    int upsertMember(@Param("workspaceId") String workspaceId, @Param("userId") String userId,
                     @Param("role") String role);

    int removeMember(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

    List<Membership> listMembers(@Param("workspaceId") String workspaceId);
}
