package com.mediaworkspace.application.service;

import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.model.UploadSession;
import com.mediaworkspace.application.port.repository.UploadRepository;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.contracts.model.Role;
import com.mediaworkspace.domain.access.RolePolicy;

/**
 * Resolves an upload session only if the caller is allowed to see it.
 *
 * <p>A non-member and a member looking at somebody else's session both receive "not found". The
 * check is a separate collaborator rather than a private method so that every use case really does
 * pass through it: the authorization cannot be skipped by calling a service directly.
 */
public class UploadAccessGuard {

    private final UploadRepository uploads;
    private final WorkspaceRepository workspaces;
    private final RolePolicy rolePolicy = new RolePolicy();

    public UploadAccessGuard(UploadRepository uploads, WorkspaceRepository workspaces) {
        this.uploads = uploads;
        this.workspaces = workspaces;
    }

    /**
     * Loads a session the actor may manage.
     *
     * @throws ApplicationException with {@code NOT_FOUND} when the session is absent, the actor is
     *                              not a member of its space, or the actor is a member without the
     *                              right to manage this particular session
     */
    public UploadSession requireVisible(String actorId, String uploadId) {
        UploadSession session = uploads.findById(uploadId)
                .orElseThrow(() -> ApplicationException.notFound("upload not found", uploadId));
        Role role = workspaces.roleOf(session.workspaceId(), actorId).orElse(null);
        if (!rolePolicy.isVisible(role)
                || !rolePolicy.mayManageUpload(role, actorId, session.ownerId())) {
            throw ApplicationException.notFound("upload not found", uploadId);
        }
        return session;
    }
}
