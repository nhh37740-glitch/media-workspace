package com.mediaworkspace.persistence.repository;

import com.mediaworkspace.application.port.repository.AuditRepository;
import com.mediaworkspace.persistence.mapper.AuditMapper;

/** MyBatis implementation of {@link AuditRepository}. */
public class AuditRepositoryAdapter implements AuditRepository {

    private final AuditMapper mapper;

    public AuditRepositoryAdapter(AuditMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean appendFromEvent(String workspaceId, String actorId, String action, String resourceId,
                                   String requestId, String detailJson, String sourceEventId) {
        return mapper.appendFromEvent(workspaceId, actorId, action, resourceId, requestId,
                detailJson, sourceEventId) > 0;
    }
}
