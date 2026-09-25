package com.mediaworkspace.worker.config;

import com.mediaworkspace.application.config.MediaWorkspaceProperties;
import com.mediaworkspace.application.port.diagnostics.BoundaryTrace;
import com.mediaworkspace.application.port.messaging.EventPublisher;
import com.mediaworkspace.application.port.messaging.EventSerializer;
import com.mediaworkspace.application.port.repository.InboxRepository;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.port.repository.OutboxRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.application.port.repository.UploadRepository;
import com.mediaworkspace.application.port.storage.MediaStorage;
import com.mediaworkspace.application.port.transcode.Transcoder;
import com.mediaworkspace.application.service.DeadLetterService;
import com.mediaworkspace.application.service.OutboxPublisherService;
import com.mediaworkspace.application.service.StorageMaintenanceService;
import com.mediaworkspace.application.service.TaskExecutionService;
import com.mediaworkspace.application.service.TaskIntakeService;
import com.mediaworkspace.application.service.TaskPublicationService;
import com.mediaworkspace.application.service.TaskRecoveryService;
import com.mediaworkspace.messaging.RequestEventConsumer;
import com.mediaworkspace.messaging.TopicNames;
import com.mediaworkspace.storage.LocalMediaStorage;
import com.mediaworkspace.transcode.ProcessTranscoder;
import com.mediaworkspace.worker.execution.ExecutionSlotPool;
import com.mediaworkspace.worker.execution.RunningExecutions;
import com.mediaworkspace.worker.execution.WorkerIdentity;
import com.mediaworkspace.worker.trace.Slf4jBoundaryTrace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Wires the worker process.
 *
 * <p>The worker shares every use case with the API but composes a different set of adapters: it gets
 * the transcoding adapter, which the API does not, and it does not get the web layer. The
 * transcoding adapter's binaries are resolved once here from configuration, so a deployment that
 * moves FFmpeg only changes a property.
 */
@Configuration
@EnableScheduling
public class WorkerConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RandomGenerator randomGenerator() {
        // Jitter and recovery ordering are not security decisions, but a predictable seed would make
        // simultaneous retries align exactly, which is the thing the jitter exists to prevent.
        return new SecureRandom();
    }

    @Bean
    public WorkerIdentity workerIdentity() {
        return WorkerIdentity.generate();
    }

    @Bean
    public BoundaryTrace boundaryTrace() {
        return new Slf4jBoundaryTrace();
    }

    @Bean
    public MediaStorage mediaStorage(@Value("${mediaworkspace.storage.root}") String root,
                                     @Value("${mediaworkspace.storage.fsync-on-publish:false}") boolean fsync) {
        return new LocalMediaStorage(Path.of(root), fsync);
    }

    @Bean
    public Transcoder transcoder(@Value("${mediaworkspace.transcode.ffmpeg:ffmpeg}") String ffmpeg,
                                 @Value("${mediaworkspace.transcode.ffprobe:ffprobe}") String ffprobe) {
        return new ProcessTranscoder(ffprobe, ffmpeg);
    }

    @Bean
    public MediaWorkspaceProperties mediaWorkspaceProperties(
            @Value("${mediaworkspace.limits.chunk-size-bytes}") int chunkSize,
            @Value("${mediaworkspace.limits.max-upload-size-bytes}") long maxUploadSize,
            @Value("${mediaworkspace.limits.workspace-quota-bytes}") long workspaceQuota,
            @Value("${mediaworkspace.limits.max-open-uploads-per-user}") int maxOpenUploads,
            @Value("${mediaworkspace.limits.max-unfinished-tasks}") int maxUnfinishedTasks,
            @Value("${mediaworkspace.limits.worker-concurrency}") int workerConcurrency,
            @Value("${mediaworkspace.limits.open-upload-ttl-hours}") long openUploadTtlHours,
            @Value("${mediaworkspace.limits.gc-grace-period-hours}") long gcGraceHours,
            @Value("${mediaworkspace.limits.max-active-shares-per-media}") int maxShares,
            @Value("${mediaworkspace.transcode.task-deadline-minutes}") long taskDeadlineMinutes) {
        return new MediaWorkspaceProperties(
                chunkSize, maxUploadSize, workspaceQuota, maxOpenUploads, maxUnfinishedTasks,
                workerConcurrency,
                Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(taskDeadlineMinutes),
                Duration.ofHours(openUploadTtlHours), Duration.ofHours(gcGraceHours), maxShares);
    }

    @Bean
    public ExecutionSlotPool executionSlotPool(MediaWorkspaceProperties properties) {
        return new ExecutionSlotPool(properties.workerConcurrency());
    }

    @Bean
    public RunningExecutions runningExecutions() {
        return new RunningExecutions();
    }

    @Bean
    public TaskPublicationService taskPublicationService(TaskRepository tasks) {
        return new TaskPublicationService(tasks);
    }

    @Bean
    public TaskExecutionService taskExecutionService(TaskRepository tasks, TaskPublicationService publications,
                                                     MediaRepository media, MediaStorage storage,
                                                     Transcoder transcoder,
                                                     MediaWorkspaceProperties properties,
                                                     RandomGenerator random, Clock clock) {
        return new TaskExecutionService(tasks, publications, media, storage, transcoder, properties,
                random, clock);
    }

    @Bean
    public TaskIntakeService taskIntakeService(InboxRepository inbox, TaskRepository tasks, Clock clock) {
        return new TaskIntakeService(inbox, tasks, clock);
    }

    /**
     * The consumer that turns request events into QUEUED tasks.
     *
     * <p>Registered explicitly, and only here. {@code @KafkaListener} is applied by a bean
     * post-processor that walks the beans of the context: an annotated class that is not a bean gets
     * no listener at all, and the failure is silent - no error, no warning, just events that are
     * produced, accepted by the broker and never read. {@code ListenerRegistrationTest} asserts this
     * bean exists, and that the API does not register one, so the ownership of request consumption
     * stays visible.
     */
    @Bean
    public RequestEventConsumer requestEventConsumer(TaskIntakeService intake,
                                                     DeadLetterService deadLetters,
                                                     EventSerializer serializer,
                                                     TopicNames topics) {
        return new RequestEventConsumer(intake, deadLetters, serializer, topics);
    }

    @Bean
    public DeadLetterService deadLetterService(InboxRepository inbox, OutboxRepository outbox, Clock clock) {
        return new DeadLetterService(inbox, outbox, clock);
    }

    @Bean
    public OutboxPublisherService outboxPublisherService(OutboxRepository outbox, EventPublisher publisher,
                                                         Clock clock) {
        return new OutboxPublisherService(outbox, publisher, clock);
    }

    /**
     * Recovery runs in the worker rather than the API: the worker is the process that owns leases,
     * and a recovered task is immediately claimable here.
     */
    @Bean
    public TaskRecoveryService taskRecoveryService(TaskRepository tasks, RandomGenerator random) {
        return new TaskRecoveryService(tasks, random);
    }

    @Bean
    public StorageMaintenanceService storageMaintenanceService(MediaStorage storage,
                                                               UploadRepository uploads,
                                                               MediaRepository media,
                                                               MediaWorkspaceProperties properties) {
        return new StorageMaintenanceService(storage, uploads, media, properties.gcGracePeriod());
    }

    /**
     * Scheduling pool for claim, renewal, outbox and maintenance.
     *
     * <p>Four threads so a pass that blocks on the database cannot stop the lease renewal: a renewal
     * delayed behind a stuck maintenance pass would let a valid lease lapse and hand a running task
     * to a second worker.
     *
     * <p>Marked as daemon so a shutdown that is skipped cannot leave the process alive on these
     * threads. The worker's normal shutdown path still drains in-flight executions explicitly.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("mw-worker-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setDaemon(true);
        return scheduler;
    }
}
