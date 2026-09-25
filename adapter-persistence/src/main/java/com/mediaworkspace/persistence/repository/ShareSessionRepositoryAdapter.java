package com.mediaworkspace.persistence.repository;

import com.mediaworkspace.application.model.ShareSession;
import com.mediaworkspace.application.port.repository.ShareSessionRepository;
import com.mediaworkspace.persistence.mapper.ShareMapper;

import java.time.Instant;
import java.util.Optional;

/** MyBatis implementation of {@link ShareSessionRepository}. */
public class ShareSessionRepositoryAdapter implements ShareSessionRepository {

    private final ShareMapper mapper;

    public ShareSessionRepositoryAdapter(ShareMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(ShareSession session) {
        mapper.insertSession(session);
    }

    @Override
    public Optional<ShareSession> findById(String sessionId) {
        return Optional.ofNullable(mapper.findSessionById(sessionId));
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        return mapper.deleteExpiredSessions(limit);
    }
}
