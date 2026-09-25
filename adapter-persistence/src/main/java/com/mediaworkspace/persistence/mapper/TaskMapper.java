package com.mediaworkspace.persistence.mapper;

import com.mediaworkspace.application.model.ProcessingTaskRecord;
import com.mediaworkspace.application.model.TaskAttemptRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SQL for {@code processing_task} and {@code task_attempt}.
 *
 * <p>Every lease or deadline comparison uses {@code UTC_TIMESTAMP(6)}; no statement trusts a
 * timestamp computed by the caller. Every conditional update states the full execution identity in
 * its {@code WHERE} clause and returns the affected row count, which the caller must interpret.
 */
public interface TaskMapper {

    int insert(@Param("id") String id, @Param("mediaId") String mediaId, @Param("traceId") String traceId,
               @Param("preset") String preset);

    ProcessingTaskRecord findById(@Param("taskId") String taskId);

    ProcessingTaskRecord findByMediaId(@Param("mediaId") String mediaId);

    ProcessingTaskRecord lockById(@Param("taskId") String taskId);

    /**
     * The next due task, locked for this worker.
     *
     * <p>{@code SKIP LOCKED} lets several workers claim different rows concurrently without queueing
     * behind each other, and {@code ORDER BY next_run_at, id} keeps the order stable so a backlog
     * drains in the order it formed. The row stays locked until the surrounding transaction ends,
     * which is what makes the following {@link #claimLocked} statement race-free.
     */
    ProcessingTaskRecord findNextDue();

    /**
     * Turns the row locked by {@link #findNextDue} into a running execution.
     *
     * @return affected rows: 1 on success, 0 when the row stopped being claimable
     */
    int claimLocked(@Param("taskId") String taskId, @Param("workerId") String workerId,
                    @Param("leaseSeconds") long leaseSeconds);

    int insertAttempt(@Param("attemptId") String attemptId, @Param("taskId") String taskId,
                      @Param("generation") int generation, @Param("attempt") int attempt,
                      @Param("executionEpoch") long executionEpoch, @Param("workerId") String workerId);

    int renew(@Param("taskId") String taskId, @Param("generation") int generation,
              @Param("executionEpoch") long executionEpoch, @Param("workerId") String workerId,
              @Param("leaseSeconds") long leaseSeconds);

    /** Conditional update that publishes a result. Zero rows means the execution was superseded. */
    int completeTask(@Param("taskId") String taskId, @Param("generation") int generation,
                     @Param("executionEpoch") long executionEpoch, @Param("workerId") String workerId);

    int updateMediaReady(@Param("mediaId") String mediaId, @Param("outputKey") String outputKey,
                         @Param("posterKey") String posterKey, @Param("outputBytes") long outputBytes,
                         @Param("durationMs") long durationMs, @Param("width") int width,
                         @Param("height") int height);

    int updateMediaState(@Param("mediaId") String mediaId, @Param("status") String status,
                         @Param("outputKey") String outputKey, @Param("posterKey") String posterKey,
                         @Param("clearArtifacts") boolean clearArtifacts);

    int failTask(@Param("taskId") String taskId, @Param("generation") int generation,
                 @Param("executionEpoch") long executionEpoch, @Param("workerId") String workerId,
                 @Param("state") String state, @Param("errorCode") String errorCode,
                 @Param("retryDelayMillis") long retryDelayMillis);

    /**
     * Moves a task whose execution lease already lapsed.
     *
     * <p>Identical to {@link #failTask} except that it does not require {@code lease_until} to be in
     * the future. Recovery runs because the lease expired, so including that condition made the
     * statement match nothing and left the task RUNNING forever - the failure it is meant to repair.
     */
    int recoverStaleTask(@Param("taskId") String taskId, @Param("generation") int generation,
                         @Param("executionEpoch") long executionEpoch,
                         @Param("state") String state, @Param("errorCode") String errorCode,
                         @Param("retryDelayMillis") long retryDelayMillis);

    int updateProgress(@Param("taskId") String taskId, @Param("generation") int generation,
                       @Param("executionEpoch") long executionEpoch, @Param("workerId") String workerId,
                       @Param("percent") int percent);

    int markQueued(@Param("taskId") String taskId, @Param("generation") int generation);

    /** Cancels a non-terminal task, invalidating any in-flight execution by bumping the epoch. */
    int cancelTask(@Param("taskId") String taskId, @Param("errorCode") String errorCode);

    /** Starts a new generation: clears progress, attempt and error, and returns to WAITING_EVENT. */
    int retryTask(@Param("taskId") String taskId);

    Integer currentGeneration(@Param("taskId") String taskId);

    int finishAttempt(@Param("attemptId") String attemptId, @Param("state") String state,
                      @Param("errorCode") String errorCode, @Param("errorSummary") String errorSummary,
                      @Param("exitCode") Integer exitCode);

    int finishOpenAttempts(@Param("taskId") String taskId, @Param("generation") int generation,
                           @Param("state") String state, @Param("errorCode") String errorCode,
                           @Param("errorSummary") String errorSummary);

    TaskAttemptRecord findOpenAttempt(@Param("taskId") String taskId, @Param("generation") int generation);

    List<TaskAttemptRecord> listAttempts(@Param("taskId") String taskId, @Param("offset") int offset,
                                         @Param("limit") int limit);

    long countAttempts(@Param("taskId") String taskId);

    List<ProcessingTaskRecord> findExpiredRunning(@Param("limit") int limit);

    List<ProcessingTaskRecord> findActiveForDeletedMedia(@Param("limit") int limit);

    /** How many tasks are not in a terminal state; compared against the counter row. */
    int countUnfinished();
}
