package com.mediaworkspace.application.service;

import com.mediaworkspace.application.config.MediaWorkspaceProperties;
import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.model.MediaRecord;
import com.mediaworkspace.application.model.PublishedArtifacts;
import com.mediaworkspace.application.model.TaskLease;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.application.port.storage.MediaStorage;
import com.mediaworkspace.application.port.transcode.CancellationToken;
import com.mediaworkspace.application.port.transcode.ProbeResult;
import com.mediaworkspace.application.port.transcode.ProbeSpec;
import com.mediaworkspace.application.port.transcode.TranscodeException;
import com.mediaworkspace.application.port.transcode.TranscodeResult;
import com.mediaworkspace.application.port.transcode.TranscodeSpec;
import com.mediaworkspace.application.port.transcode.Transcoder;
import com.mediaworkspace.contracts.model.TaskErrorCode;
import com.mediaworkspace.contracts.model.TaskState;
import com.mediaworkspace.domain.task.BackoffPolicy;
import com.mediaworkspace.domain.task.FailureClassifier;
import com.mediaworkspace.domain.task.TaskAction;
import com.mediaworkspace.domain.task.TaskStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Executes one claimed task: inspect the source, encode into an execution-private directory,
 * validate the artifacts and publish them under the lease.
 *
 * <p>Three properties define this class:
 * <ul>
 *   <li>the claim is only attempted when the caller already holds a local execution permit, so a
 *       task is never taken without somewhere to run it and no unbounded queue forms;</li>
 *   <li>every output path contains the generation and the execution epoch, so a superseded
 *       execution can only write into its own directory and cannot overwrite a newer result;</li>
 *   <li>a zero exit code is necessary but not sufficient. The produced files are probed before the
 *       publish transaction is attempted, and the publish itself is a conditional update whose
 *       zero-row result means "discard this result", never "retry the write".</li>
 * </ul>
 */
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    private final TaskRepository tasks;
    private final MediaRepository media;
    private final MediaStorage storage;
    private final Transcoder transcoder;
    private final MediaWorkspaceProperties properties;
    private final TaskStateMachine stateMachine = new TaskStateMachine();
    private final FailureClassifier classifier = new FailureClassifier();
    private final BackoffPolicy backoff;
    private final Clock clock;

    public TaskExecutionService(TaskRepository tasks, MediaRepository media, MediaStorage storage,
                                Transcoder transcoder, MediaWorkspaceProperties properties,
                                RandomGenerator random, Clock clock) {
        this.tasks = tasks;
        this.media = media;
        this.storage = storage;
        this.transcoder = transcoder;
        this.properties = properties;
        this.backoff = new BackoffPolicy(random);
        this.clock = clock;
    }

    /**
     * Claims the next due task, if the caller can run it.
     *
     * @return the lease, or empty when nothing is due
     */
    public Optional<TaskLease> claim(String workerId) {
        return tasks.claim(workerId, properties.taskLeaseDuration());
    }

    /** Extends a lease. A {@code false} result means the execution lost its right to publish. */
    public boolean renew(TaskLease lease) {
        return tasks.renew(lease, properties.taskLeaseDuration());
    }

    /** Terminal outcome of one execution, as reported to the scheduler. */
    public enum ExecutionOutcome {
        /** The artifact set was published and the media is READY. */
        PUBLISHED,
        /** The execution failed and the task moved to RETRY_WAIT. */
        RETRY_SCHEDULED,
        /** The execution failed and the task is terminally FAILED. */
        FAILED,
        /** The execution was cancelled or superseded; nothing was written. */
        DISCARDED
    }

    /**
     * Runs one execution to completion.
     *
     * <p>This method never throws for a media failure: every classified failure is recorded on the
     * task and reported through the return value, because an exception here would only be caught by
     * the scheduler and would lose the classification.
     */
    public ExecutionOutcome run(TaskLease lease, CancellationToken cancel) {
        String mediaId = lease.task().mediaId();
        String attemptId = lease.attemptId();
        Instant now = clock.instant();

        Optional<MediaRecord> maybeMedia = media.findVisible(mediaId);
        if (maybeMedia.isEmpty()) {
            // The media was deleted while the task waited. Cancel rather than fail: there is
            // nothing to retry and the user asked for the media to be gone.
            tasks.cancel(lease.task().id(), "MEDIA_DELETED");
            return ExecutionOutcome.DISCARDED;
        }
        MediaRecord source = maybeMedia.get();
        ProgressReporter progress = new ProgressReporter(tasks, clock, lease.identity());

        Path outputFile;
        Path posterFile;
        try {
            Path sourcePath = storage.resolve(source.sourceKey());
            ProbeResult probe = transcoder.probe(ProbeSpec.of(sourcePath), cancel);
            if (!probe.hasVideoStream()) {
                return fail(lease, TaskErrorCode.UNSUPPORTED_MEDIA,
                        "the container has no video stream", null, attemptId);
            }
            Path attemptDir = prepareAttemptDirectory(lease);
            outputFile = attemptDir.resolve("output.mp4");
            posterFile = attemptDir.resolve("poster.jpg");

            TranscodeResult result = transcoder.execute(
                    new TranscodeSpec(sourcePath, outputFile, posterFile, lease.task().preset(),
                            properties.taskDeadline(), 2, 64 * 1024),
                    progress::report, cancel);
            return publish(lease, result, attemptId);
        } catch (TranscodeException e) {
            return fail(lease, e.errorCode(), e.getMessage(), e.exitCode(), attemptId, e.standardErrorTail());
        } catch (MediaStorage.StorageException | ApplicationException e) {
            return fail(lease, TaskErrorCode.SOURCE_MISSING,
                    "the immutable original could not be read", null, attemptId);
        } catch (RuntimeException e) {
            log.error("unexpected failure while executing task {}", lease.task().id(), e);
            return fail(lease, TaskErrorCode.INTERNAL_ERROR,
                    "unexpected execution failure", null, attemptId);
        }
    }

    private Path prepareAttemptDirectory(TaskLease lease) throws MediaStorage.StorageException {
        // prepareForWrite creates the parent chain and returns the absolute path of the file the
        // child process will write; the caller derives the poster path from the same directory.
        return storage.prepareForWrite(attemptPrefix(lease) + "/output.mp4").getParent();
    }

    /** {@code derived/{mediaId}/{generation}/{executionEpoch}} — one directory per execution. */
    static String attemptPrefix(TaskLease lease) {
        return "derived/" + lease.task().mediaId() + "/" + lease.task().generation()
                + "/" + lease.task().executionEpoch();
    }

    /**
     * Promotes the validated artifacts into immutable keys and publishes them.
     *
     * <p>The promotion happens before the transaction, so the files are complete and immutable by
     * the time a row can reference them. The transaction itself re-checks the generation, the
     * epoch, the worker identity, the lease deadline and the media's visibility; a zero-row result
     * means a newer execution owns the row and this result must be discarded without retrying.
     */
    private ExecutionOutcome publish(TaskLease lease, TranscodeResult result, String attemptId) {
        String prefix = attemptPrefix(lease);
        try (var outputIn = Files.newInputStream(result.outputFile());
             var posterIn = Files.newInputStream(result.posterFile())) {
            storage.putImmutable(prefix + "/output.mp4", outputIn);
            storage.putImmutable(prefix + "/poster.jpg", posterIn);
        } catch (IOException e) {
            return fail(lease, TaskErrorCode.OUTPUT_INVALID,
                    "the produced artifacts could not be published", null, attemptId);
        }

        PublishedArtifacts artifacts = new PublishedArtifacts(
                prefix + "/output.mp4", prefix + "/poster.jpg",
                result.outputBytes(), result.durationMs(), result.width(), result.height());
        boolean published = tasks.complete(lease, artifacts, attemptId);
        if (!published) {
            log.info("task {} execution {} was superseded; its output is left unreferenced",
                    lease.task().id(), lease.task().executionEpoch());
            return ExecutionOutcome.DISCARDED;
        }
        return ExecutionOutcome.PUBLISHED;
    }

    private ExecutionOutcome fail(TaskLease lease, TaskErrorCode errorCode, String summary,
                                  Integer exitCode, String attemptId) {
        return fail(lease, errorCode, summary, exitCode, attemptId, null);
    }

    private ExecutionOutcome fail(TaskLease lease, TaskErrorCode errorCode, String summary,
                                  Integer exitCode, String attemptId, String standardErrorTail) {
        Instant now = clock.instant();
        int attemptsConsumed = lease.task().attempt();
        // The domain decides whether this failure is terminal, so the attempt budget is not
        // re-implemented here: a permanent code fails immediately, a transient one only after the
        // budget for this generation is used up.
        TaskAction action = classifier.isRetryable(errorCode)
                ? TaskAction.RETRYABLE_FAILURE
                : TaskAction.PERMANENT_FAILURE;
        Optional<TaskState> target = stateMachine.next(TaskState.RUNNING, action, attemptsConsumed);
        if (target.isEmpty()) {
            log.error("state machine refused a failure transition for task {} in state {}",
                    lease.task().id(), lease.task().state());
            return ExecutionOutcome.DISCARDED;
        }
        boolean terminal = target.get() == TaskState.FAILED;
        // The deadline itself is computed by the database from this delay, so the worker's own
        // clock never decides when a task becomes claimable again.
        Duration retryDelay = terminal ? Duration.ZERO : backoff.delayAfter(attemptsConsumed);
        String safeSummary = sanitize(summary, standardErrorTail);

        if (!tasks.fail(lease, errorCode, safeSummary, exitCode, retryDelay, terminal)) {
            return ExecutionOutcome.DISCARDED;
        }
        if (terminal) {
            log.info("task {} failed terminally: {}", lease.task().id(), errorCode);
            return ExecutionOutcome.FAILED;
        }
        return ExecutionOutcome.RETRY_SCHEDULED;
    }

    /**
     * Truncates and strips a failure summary before it reaches the attempt record.
     *
     * <p>The stored summary is shown on the task detail page to ordinary users, so it must not
     * carry server paths, SQL or whole log lines.
     */
    static String sanitize(String summary, String standardErrorTail) {
        String base = summary == null ? "" : summary;
        if (standardErrorTail != null && !standardErrorTail.isBlank()) {
            base = base + " | stderr tail: " + standardErrorTail;
        }
        String flattened = base.replaceAll("\\s+", " ").trim();
        int max = 1024;
        if (flattened.length() > max) {
            flattened = flattened.substring(flattened.length() - max);
        }
        return flattened.replaceAll("(/[A-Za-z0-9._-]+){3,}", "<path>");
    }
}
