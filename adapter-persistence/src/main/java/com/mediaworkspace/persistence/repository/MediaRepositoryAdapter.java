package com.mediaworkspace.persistence.repository;

import com.mediaworkspace.application.model.MediaQuery;
import com.mediaworkspace.application.model.MediaRecord;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.persistence.mapper.MediaMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** MyBatis implementation of {@link MediaRepository}. */
public class MediaRepositoryAdapter implements MediaRepository {

    /** Character used to escape LIKE metacharacters; it must not appear unescaped in a pattern. */
    private static final char ESCAPE = '!';

    private final MediaMapper mapper;

    public MediaRepositoryAdapter(MediaMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(MediaRecord media) {
        mapper.insert(media);
    }

    @Override
    public Optional<MediaRecord> findVisible(String mediaId) {
        return Optional.ofNullable(mapper.findVisible(mediaId));
    }

    @Override
    public Optional<MediaRecord> lockVisible(String mediaId) {
        return Optional.ofNullable(mapper.lockVisible(mediaId));
    }

    @Override
    public List<MediaRecord> search(MediaQuery query) {
        return mapper.search(query.workspaceId(), escapeLike(query.titleQuery()),
                query.pageSize(), query.offset());
    }

    @Override
    public long count(MediaQuery query) {
        return mapper.count(query.workspaceId(), escapeLike(query.titleQuery()));
    }

    @Override
    public boolean rename(String mediaId, String title, long expectedVersion) {
        return mapper.rename(mediaId, title, expectedVersion) > 0;
    }

    @Override
    public boolean markDeleted(String mediaId) {
        return mapper.markDeleted(mediaId) > 0;
    }

    @Override
    public Optional<MediaRecord> findAnyIncludingDeleted(String mediaId) {
        return Optional.ofNullable(mapper.findAnyIncludingDeleted(mediaId));
    }

    @Override
    public boolean isStorageKeyReferenced(String storageKey) {
        return mapper.countStorageKeyReferences(storageKey) > 0;
    }

    @Override
    public List<MediaRecord> findDeletedBefore(Duration lookback, int limit) {
        return mapper.findDeletedBefore(lookback.toSeconds(), limit);
    }

    /**
     * Neutralizes LIKE metacharacters so a user typing {@code %} or {@code _} searches for that
     * character instead of matching every row.
     */
    static String escapeLike(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder escaped = new StringBuilder(raw.length() + 8);
        for (char c : raw.toCharArray()) {
            if (c == ESCAPE || c == '%' || c == '_') {
                escaped.append(ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
