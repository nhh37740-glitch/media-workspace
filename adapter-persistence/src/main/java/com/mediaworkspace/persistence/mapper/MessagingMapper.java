package com.mediaworkspace.persistence.mapper;

import com.mediaworkspace.application.model.OutboxRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** SQL for {@code outbox_event}, {@code inbox_event} and {@code poison_message}. */
public interface MessagingMapper {

    int appendOutbox(@Param("o") OutboxRecord record);

    /**
     * Claims pending rows for one publisher.
     *
     * <p>Rows whose claim lease lapsed are picked up again, so a publisher that died mid-batch does
     * not strand its records. Selecting and claiming happen in one statement to avoid a window in
     * which two publishers both own a row.
     */
    int claimOutbox(@Param("claimToken") String claimToken, @Param("claimSeconds") long claimSeconds,
                    @Param("eventIds") List<String> eventIds);

    List<OutboxRecord> findClaimable(@Param("limit") int limit);

    int markPublished(@Param("eventId") String eventId, @Param("claimToken") String claimToken);

    int reschedule(@Param("eventId") String eventId, @Param("claimToken") String claimToken,
                   @Param("delayMillis") long delayMillis);

    int releaseExpiredClaims();

    OutboxRecord findOutbox(@Param("eventId") String eventId);

    /** Number of rows still waiting; a sustained non-zero value is what an operator watches. */
    int countPending();

    /**
     * Records an event as processed for a consumer group.
     *
     * <p>{@code INSERT IGNORE} makes a redelivery a no-op at the database level. The caller must
     * distinguish "already present" from other failures by re-reading, not by swallowing errors.
     */
    int insertInbox(@Param("consumerGroup") String consumerGroup, @Param("eventId") String eventId,
                    @Param("bodyHash") String bodyHash);

    String findInboxBodyHash(@Param("consumerGroup") String consumerGroup,
                             @Param("eventId") String eventId);

    int insertPoison(@Param("topic") String topic, @Param("partitionId") int partitionId,
                     @Param("offsetId") long offsetId, @Param("errorCode") String errorCode,
                     @Param("bodyHash") String bodyHash);

    int countPoison(@Param("topic") String topic, @Param("partitionId") int partitionId,
                    @Param("offsetId") long offsetId);
}
