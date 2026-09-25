package com.mediaworkspace.persistence.repository;

import com.mediaworkspace.application.model.IdempotencyRecord;
import com.mediaworkspace.application.port.repository.IdempotencyRepository;
import com.mediaworkspace.persistence.mapper.IdempotencyMapper;

import java.util.Optional;

/** MyBatis implementation of {@link IdempotencyRepository}. */
public class IdempotencyRepositoryAdapter implements IdempotencyRepository {

    private final IdempotencyMapper mapper;

    public IdempotencyRepositoryAdapter(IdempotencyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<IdempotencyRecord> find(String userId, String route, String resourceId, String keyHash) {
        return Optional.ofNullable(mapper.find(userId, route, resourceId, keyHash));
    }

    @Override
    public void insert(IdempotencyRecord record) {
        mapper.insert(record);
    }
}
