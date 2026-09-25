package com.mediaworkspace.persistence.repository;

import com.mediaworkspace.application.model.OutboxRecord;
import com.mediaworkspace.application.port.repository.OutboxRepository;
import com.mediaworkspace.persistence.mapper.MessagingMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * MyBatis implementation of {@link OutboxRepository}.
 *
 * <p>Claiming is two steps - read the candidates, then claim them conditionally - both inside the
 * caller's transaction. The conditional update is what makes it safe: two publishers that read the
 * same batch cannot both claim it, because the second update matches no rows.
 */
public class OutboxRepositoryAdapter implements OutboxRepository {

    private final MessagingMapper mapper;

    public OutboxRepositoryAdapter(MessagingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(OutboxRecord record) {
        mapper.appendOutbox(record);
    }

    @Override
    public List<OutboxRecord> claimBatch(String claimToken, int limit, Instant now, Instant claimUntil) {
        Duration lease = Duration.between(now, claimUntil);
        List<OutboxRecord> candidates = mapper.findClaimable(limit);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> ids = candidates.stream().map(OutboxRecord::eventId).toList();
        mapper.claimOutbox(claimToken, Math.max(1, lease.toSeconds()), ids);
        // Re-read so the caller sees the rows it actually won, with their updated claim fields,
        // rather than the candidates it merely looked at.
        return ids.stream()
                .map(mapper::findOutbox)
                .filter(record -> record != null && claimToken.equals(record.claimToken()))
                .toList();
    }

    @Override
    public boolean markPublished(String eventId, String claimToken, Instant now) {
        return mapper.markPublished(eventId, claimToken) > 0;
    }

    @Override
    public void reschedule(String eventId, String claimToken, Instant nextRunAt) {
        long delayMillis = Math.max(0, Duration.between(Instant.now(), nextRunAt).toMillis());
        mapper.reschedule(eventId, claimToken, delayMillis);
    }

    @Override
    public int releaseExpiredClaims(Instant now) {
        return mapper.releaseExpiredClaims();
    }

    /** Rows not yet acknowledged; a sustained non-zero value is what an operator watches. */
    public int pendingCount() {
        return mapper.countPending();
    }
}
