package com.mediaworkspace.application.service;

import com.mediaworkspace.application.config.MediaWorkspaceProperties;
import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.model.Membership;
import com.mediaworkspace.application.model.Workspace;
import com.mediaworkspace.application.port.repository.UserRepository;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.contracts.dto.MemberView;
import com.mediaworkspace.contracts.dto.SpaceView;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.contracts.model.Role;
import com.mediaworkspace.domain.access.RolePolicy;
import com.mediaworkspace.domain.access.SpaceAction;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Workspaces and their members.
 *
 * <p>Authorization is decided inside the transaction that performs the change, not only in the
 * HTTP layer: a membership revoked a moment ago must not still be able to act. The role matrix
 * itself lives in {@code media-domain}.
 */
public class WorkspaceService {

    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final MediaWorkspaceProperties properties;
    private final RolePolicy rolePolicy = new RolePolicy();

    public WorkspaceService(WorkspaceRepository workspaces, UserRepository users,
                            MediaWorkspaceProperties properties) {
        this.workspaces = workspaces;
        this.users = users;
        this.properties = properties;
    }

    /** Creates a space and makes the creator its single OWNER. */
    @Transactional
    public SpaceView createSpace(String actorId, String name) {
        String spaceId = workspaces.insert(name, actorId, properties.workspaceQuotaBytes());
        return new SpaceView(spaceId, name, Role.OWNER.name());
    }

    /** Spaces the caller belongs to. */
    @Transactional(readOnly = true)
    public List<SpaceView> listSpaces(String actorId) {
        return workspaces.listForUser(actorId).stream()
                .map(space -> new SpaceView(space.id(), space.name(), space.role().name()))
                .toList();
    }

    /**
     * Adds a member or changes an existing member's role.
     *
     * <p>The single OWNER row is never modified through this path, so a space cannot lose its
     * owner. An unknown target user is reported as absent rather than created.
     */
    @Transactional
    public MemberView putMember(String actorId, String spaceId, String targetUserId, Role role) {
        requireAction(actorId, spaceId, SpaceAction.MANAGE_MEMBERS);
        if (role == Role.OWNER) {
            throw new ApplicationException(ApiErrorCode.VALIDATION_FAILED,
                    "the owner role cannot be granted through this endpoint", spaceId);
        }
        if (!users.existsById(targetUserId)) {
            throw ApplicationException.notFound("user not found", targetUserId);
        }
        Workspace workspace = workspaces.findById(spaceId)
                .orElseThrow(() -> ApplicationException.notFound("space not found", spaceId));
        if (workspace.ownerId().equals(targetUserId)) {
            throw ApplicationException.conflict("the owner membership cannot be changed", spaceId);
        }
        workspaces.upsertMember(spaceId, targetUserId, role);
        return new MemberView(targetUserId, usernameOf(targetUserId), role.name());
    }

    /** Removes a member. The OWNER row is protected. */
    @Transactional
    public void removeMember(String actorId, String spaceId, String targetUserId) {
        requireAction(actorId, spaceId, SpaceAction.MANAGE_MEMBERS);
        Workspace workspace = workspaces.findById(spaceId)
                .orElseThrow(() -> ApplicationException.notFound("space not found", spaceId));
        if (workspace.ownerId().equals(targetUserId)) {
            throw ApplicationException.conflict("the owner membership cannot be removed", spaceId);
        }
        workspaces.removeMember(spaceId, targetUserId);
    }

    @Transactional(readOnly = true)
    public List<MemberView> listMembers(String actorId, String spaceId) {
        requireAction(actorId, spaceId, SpaceAction.MANAGE_MEMBERS);
        return workspaces.listMembers(spaceId).stream()
                .map(member -> new MemberView(member.userId(), member.username(), member.role().name()))
                .toList();
    }

    /** Resolves a workspace for the caller, reporting a non-member as "not found". */
    @Transactional(readOnly = true)
    public Workspace requireVisible(String actorId, String spaceId) {
        return workspaces.findVisibleToUser(spaceId, actorId)
                .orElseThrow(() -> ApplicationException.notFound("space not found", spaceId));
    }

    /**
     * Authoritative permission check used by other use cases.
     *
     * @throws ApplicationException {@code NOT_FOUND} when the caller is not a member, so that a
     *                              space the caller cannot see is indistinguishable from one that
     *                              does not exist
     */
    public void requireAction(String actorId, String spaceId, SpaceAction action) {
        Role role = workspaces.roleOf(spaceId, actorId).orElse(null);
        if (!rolePolicy.isVisible(role)) {
            throw ApplicationException.notFound("space not found", spaceId);
        }
        if (!rolePolicy.allows(role, action)) {
            throw ApplicationException.forbidden("role does not permit this action", spaceId);
        }
    }

    private String usernameOf(String userId) {
        return users.findById(userId).map(account -> account.username()).orElse("");
    }
}
