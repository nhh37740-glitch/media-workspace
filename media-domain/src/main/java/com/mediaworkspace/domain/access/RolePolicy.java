package com.mediaworkspace.domain.access;

import com.mediaworkspace.contracts.model.Role;

/**
 * Role rules for workspace actions.
 *
 * <p>This is the server-side decision. The web client may hide a button, but hiding a control is
 * never the authorization boundary: every mutating request is re-checked inside the transaction
 * that performs it, so revoking a membership stops an in-flight request.
 */
public final class RolePolicy {

    /**
     * Whether {@code role} may perform {@code action}.
     *
     * @param role   the actor's role, or {@code null} when the actor is not a member
     * @param action the attempted action
     */
    public boolean allows(Role role, SpaceAction action) {
        return role != null && role.atLeast(action.required());
    }

    /** A non-member is indistinguishable from a missing resource: both are reported as absent. */
    public boolean isVisible(Role role) {
        return role != null;
    }

    /**
     * Whether the actor may act on a specific upload session: its uploader, or an OWNER.
     *
     * <p>The role floor is applied first, so a VIEWER who happens to share an id with the uploader
     * is still refused. Ownership narrows the permission, it never widens it.
     */
    public boolean mayManageUpload(Role role, String actorId, String uploaderId) {
        if (!allows(role, SpaceAction.UPLOAD)) {
            return false;
        }
        return role == Role.OWNER || (actorId != null && actorId.equals(uploaderId));
    }

    /**
     * A share may be revoked by the member who created it, or by an OWNER. As above, the role
     * floor applies before the ownership comparison.
     */
    public boolean mayRevokeShare(Role role, String actorId, String creatorId) {
        if (!allows(role, SpaceAction.SHARE)) {
            return false;
        }
        return role == Role.OWNER || (actorId != null && actorId.equals(creatorId));
    }
}
