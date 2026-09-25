package com.mediaworkspace.persistence.repository;

import com.mediaworkspace.application.model.ShareLinkRecord;
import com.mediaworkspace.application.port.repository.ShareRepository;
import com.mediaworkspace.persistence.mapper.ShareMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** MyBatis implementation of {@link ShareRepository}. */
public class ShareRepositoryAdapter implements ShareRepository {

    private final ShareMapper mapper;

    public ShareRepositoryAdapter(ShareMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(ShareLinkRecord link) {
        mapper.insertLink(link);
    }

    @Override
    public Optional<ShareLinkRecord> findById(String shareId) {
        return Optional.ofNullable(mapper.findLinkById(shareId));
    }

    @Override
    public Optional<ShareLinkRecord> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(mapper.findLinkByTokenHash(tokenHash));
    }

    @Override
    public List<ShareLinkRecord> listByMedia(String mediaId, int limit) {
        return mapper.listByMedia(mediaId, limit);
    }

    @Override
    public long countActiveForMedia(String mediaId, Instant now) {
        // Expiry is judged by the database clock inside the statement, so a caller with a skewed
        // clock cannot count a different set of shares than the one that will actually be usable.
        return mapper.countActiveForMedia(mediaId);
    }

    @Override
    public boolean revoke(String shareId, Instant revokedAt) {
        return mapper.revoke(shareId) > 0;
    }
}
