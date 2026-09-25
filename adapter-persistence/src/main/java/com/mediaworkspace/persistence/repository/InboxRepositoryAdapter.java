package com.mediaworkspace.persistence.repository;

import com.mediaworkspace.application.port.repository.InboxRepository;
import com.mediaworkspace.persistence.mapper.MessagingMapper;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;

/**
 * MyBatis implementation of {@link InboxRepository}.
 *
 * <p>The distinction between "already processed" and "something else went wrong" is made
 * deliberately narrow: only a duplicate-key violation is translated into a duplicate result. Any
 * other failure propagates, because treating a broken transaction as a duplicate would skip the
 * business update while the offset still advanced, which is exactly the way a message gets lost.
 */
public class InboxRepositoryAdapter implements InboxRepository {

    private final MessagingMapper mapper;

    public InboxRepositoryAdapter(MessagingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public IntakeResult intake(String consumerGroup, String eventId, String bodyHash, Instant now) {
        try {
            if (mapper.insertInbox(consumerGroup, eventId, bodyHash) > 0) {
                return IntakeResult.NEW;
            }
            // INSERT IGNORE reports zero rows for an existing key without raising.
            return classifyExisting(consumerGroup, eventId, bodyHash);
        } catch (DuplicateKeyException e) {
            return classifyExisting(consumerGroup, eventId, bodyHash);
        }
    }

    private IntakeResult classifyExisting(String consumerGroup, String eventId, String bodyHash) {
        String stored = mapper.findInboxBodyHash(consumerGroup, eventId);
        if (stored == null) {
            // The row disappeared between the insert and the read. Reporting CONFLICT would silently
            // drop the update, so the caller is told to treat the record as new.
            return IntakeResult.NEW;
        }
        return stored.equals(bodyHash) ? IntakeResult.DUPLICATE : IntakeResult.CONFLICT;
    }

    @Override
    public void recordPoison(String topic, int partition, long offset, String errorCode, String bodyHash,
                             Instant now) {
        mapper.insertPoison(topic, partition, offset, errorCode, bodyHash);
    }

    @Override
    public boolean isPoisonRecorded(String topic, int partition, long offset) {
        return mapper.countPoison(topic, partition, offset) > 0;
    }
}
