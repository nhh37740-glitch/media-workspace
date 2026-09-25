package com.mediaworkspace.api.web;

import com.mediaworkspace.application.service.WorkspaceService;
import com.mediaworkspace.contracts.dto.CreateSpaceRequest;
import com.mediaworkspace.contracts.dto.MemberView;
import com.mediaworkspace.contracts.dto.SpaceView;
import com.mediaworkspace.contracts.dto.UpdateMemberRoleRequest;
import com.mediaworkspace.contracts.model.Role;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Workspace and membership endpoints.
 *
 * <p>Membership changes are the clearest case for re-checking inside the transaction: an owner who
 * removes an editor while that editor's request is in flight must be able to rely on the removal
 * taking effect, which only holds if the permission is read under the same lock as the change.
 */
@RestController
@RequestMapping("/api/v1/spaces")
public class SpaceController {

    private final WorkspaceService workspaces;
    private final CurrentUser currentUser;

    public SpaceController(WorkspaceService workspaces, CurrentUser currentUser) {
        this.workspaces = workspaces;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<SpaceView> create(@Valid @RequestBody CreateSpaceRequest request) {
        SpaceView created = workspaces.createSpace(currentUser.requireId(), request.name());
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping
    public List<SpaceView> list() {
        return workspaces.listSpaces(currentUser.requireId());
    }

    @PutMapping("/{spaceId}/members/{userId}")
    public MemberView putMember(@PathVariable String spaceId, @PathVariable String userId,
                                @Valid @RequestBody UpdateMemberRoleRequest request) {
        return workspaces.putMember(currentUser.requireId(), spaceId, userId,
                Role.valueOf(request.role()));
    }

    /** Removing an already absent member is not an error: the requested end state is reached. */
    @DeleteMapping("/{spaceId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable String spaceId, @PathVariable String userId) {
        workspaces.removeMember(currentUser.requireId(), spaceId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{spaceId}/members")
    public List<MemberView> members(@PathVariable String spaceId) {
        return workspaces.listMembers(currentUser.requireId(), spaceId);
    }
}
