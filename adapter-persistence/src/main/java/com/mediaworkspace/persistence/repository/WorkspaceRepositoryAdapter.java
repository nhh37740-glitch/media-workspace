package com.mediaworkspace.persistence.repository;

import com.mediaworkspace.application.model.Membership;
import com.mediaworkspace.application.model.Workspace;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.contracts.model.Role;
import com.mediaworkspace.persistence.mapper.WorkspaceMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** MyBatis implementation of {@link WorkspaceRepository}. */
public class WorkspaceRepositoryAdapter implements WorkspaceRepository {

    private final WorkspaceMapper mapper;

    public WorkspaceRepositoryAdapter(WorkspaceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String insert(String name, String ownerId, long quotaBytes) {
        String workspaceId = UUID.randomUUID().toString();
        mapper.insert(workspaceId, name, ownerId, quotaBytes);
        // Creating a space makes the creator its single OWNER; there is no second-owner path.
        mapper.upsertMember(workspaceId, ownerId, Role.OWNER.name());
        return workspaceId;
    }

    @Override
    public Optional<Workspace> findById(String workspaceId) {
        return Optional.ofNullable(mapper.findById(workspaceId, null));
    }

    @Override
    public Optional<Workspace> findVisibleToUser(String workspaceId, String userId) {
        return Optional.ofNullable(mapper.findById(workspaceId, userId))
                .filter(workspace -> workspace.role() != null);
    }

    @Override
    public List<Workspace> listForUser(String userId) {
        return mapper.listForUser(userId);
    }

    @Override
    public Optional<Workspace> lockForReservation(String workspaceId) {
        return Optional.ofNullable(mapper.lockForReservation(workspaceId));
    }

    @Override
    public void addReservedBytes(String workspaceId, long delta) {
        mapper.addReservedBytes(workspaceId, delta);
    }

    @Override
    public void releaseReservedBytes(String workspaceId, long delta) {
        mapper.releaseReservedBytes(workspaceId, delta);
    }

    @Override
    public void moveReservedToUsed(String workspaceId, long bytes) {
        mapper.moveReservedToUsed(workspaceId, bytes);
    }

    @Override
    public void releaseUsedBytes(String workspaceId, long delta) {
        mapper.releaseUsedBytes(workspaceId, delta);
    }

    @Override
    public Optional<Role> roleOf(String workspaceId, String userId) {
        String role = mapper.roleOf(workspaceId, userId);
        return role == null ? Optional.empty() : Optional.of(Role.valueOf(role));
    }

    @Override
    public boolean upsertMember(String workspaceId, String userId, Role role) {
        return mapper.upsertMember(workspaceId, userId, role.name()) > 0;
    }

    @Override
    public boolean removeMember(String workspaceId, String userId) {
        return mapper.removeMember(workspaceId, userId) > 0;
    }

    @Override
    public List<Membership> listMembers(String workspaceId) {
        return mapper.listMembers(workspaceId).stream()
                .map(member -> new Membership(member.userId(), member.username(), member.role()))
                .toList();
    }
}
