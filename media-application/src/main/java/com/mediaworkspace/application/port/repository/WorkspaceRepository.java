package com.mediaworkspace.application.port.repository;

import com.mediaworkspace.application.model.Membership;
import com.mediaworkspace.application.model.Workspace;
import com.mediaworkspace.contracts.model.Role;

import java.util.List;
import java.util.Optional;

/** Workspaces, memberships and the source-quota counters that live on the workspace row. */
public interface WorkspaceRepository {

    String insert(String name, String ownerId, long quotaBytes);

    /**
     * Reads a workspace together with the caller's role.
     *
     * <p>A non-member yields a present workspace with a {@code null} role only if the row exists;
     * callers that must not leak existence use {@link #findVisibleToUser} instead.
     */
    Optional<Workspace> findById(String workspaceId);

    /** Reads a workspace only when the caller is a member; otherwise empty (reported as 404). */
    Optional<Workspace> findVisibleToUser(String workspaceId, String userId);

    List<Workspace> listForUser(String userId);

    /**
     * Locks the workspace row for a quota decision.
     *
     * <p>The row lock is what makes the check-then-reserve sequence atomic: two concurrent uploads
     * serialize here instead of both reading the same free balance.
     */
    Optional<Workspace> lockForReservation(String workspaceId);

    /** Increases the reservation counter by {@code delta}; the caller holds the row lock. */
    void addReservedBytes(String workspaceId, long delta);

    /** Decreases the reservation counter, never below zero. */
    void releaseReservedBytes(String workspaceId, long delta);

    /** Moves {@code bytes} from reserved to used when an original file is published. */
    void moveReservedToUsed(String workspaceId, long bytes);

    /** Decreases the used counter after a media deletion. */
    void releaseUsedBytes(String workspaceId, long delta);

    Optional<Role> roleOf(String workspaceId, String userId);

    /** Inserts or updates a membership, never touching the OWNER row. */
    boolean upsertMember(String workspaceId, String userId, Role role);

    /** Removes a membership, never removing the OWNER row. */
    boolean removeMember(String workspaceId, String userId);

    List<Membership> listMembers(String workspaceId);
}
