package com.mediaworkspace.api.config;

import com.mediaworkspace.application.config.MediaWorkspaceProperties;
import com.mediaworkspace.application.port.diagnostics.BoundaryTrace;
import com.mediaworkspace.application.port.messaging.EventPublisher;
import com.mediaworkspace.application.port.messaging.EventSerializer;
import com.mediaworkspace.application.port.repository.AuditRepository;
import com.mediaworkspace.application.port.repository.CapacityRepository;
import com.mediaworkspace.application.port.repository.IdempotencyRepository;
import com.mediaworkspace.application.port.repository.InboxRepository;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.port.repository.OutboxRepository;
import com.mediaworkspace.application.port.repository.ShareRepository;
import com.mediaworkspace.application.port.repository.ShareSessionRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.application.port.repository.UploadRepository;
import com.mediaworkspace.application.port.repository.UserRepository;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.application.port.security.PasswordHasher;
import com.mediaworkspace.application.port.security.SecretTokens;
import com.mediaworkspace.application.port.storage.MediaStorage;
import com.mediaworkspace.application.service.DeadLetterService;
import com.mediaworkspace.application.service.MediaService;
import com.mediaworkspace.application.service.OutboxPublisherService;
import com.mediaworkspace.application.service.ResultProjectionService;
import com.mediaworkspace.application.service.ShareService;
import com.mediaworkspace.application.service.TaskIntakeService;
import com.mediaworkspace.application.service.TaskService;
import com.mediaworkspace.application.service.UploadAccessGuard;
import com.mediaworkspace.application.service.UploadChunkCommitService;
import com.mediaworkspace.application.service.UploadFinalizeService;
import com.mediaworkspace.application.service.UploadFinalizeTransactionService;
import com.mediaworkspace.application.service.UploadService;
import com.mediaworkspace.application.service.WorkspaceService;
import com.mediaworkspace.messaging.ResultEventConsumer;
import com.mediaworkspace.messaging.TopicNames;
import com.mediaworkspace.api.security.BCryptPasswordHasher;
import com.mediaworkspace.api.security.SecureSecretTokens;
import com.mediaworkspace.api.trace.Slf4jBoundaryTrace;
import com.mediaworkspace.storage.LocalMediaStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Wires the application use cases into the API process.
 *
 * <p>This is the only place that decides which adapters the API gets. The use cases themselves take
 * ports, so the same classes run in the worker against the same persistence adapter but without the
 * web layer, and a test can substitute a port without touching business code.
 */
@Configuration
public class ApiServiceConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public BoundaryTrace boundaryTrace() {
        return new Slf4jBoundaryTrace();
    }

    @Bean
    public PasswordHasher passwordHasher() {
        return new BCryptPasswordHasher();
    }

    @Bean
    public SecretTokens secretTokens() {
        return new SecureSecretTokens();
    }

    @Bean
    public MediaStorage mediaStorage(@Value("${mediaworkspace.storage.root}") String root,
                                     @Value("${mediaworkspace.storage.fsync-on-publish:false}") boolean fsync) {
        return new LocalMediaStorage(Path.of(root), fsync);
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
            @Value("${mediaworkspace.limits.max-active-shares-per-media}") int maxShares) {
        return new MediaWorkspaceProperties(
                chunkSize, maxUploadSize, workspaceQuota, maxOpenUploads, maxUnfinishedTasks,
                workerConcurrency,
                Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(30),
                Duration.ofHours(openUploadTtlHours), Duration.ofHours(gcGraceHours), maxShares);
    }

    @Bean
    public UploadAccessGuard uploadAccessGuard(UploadRepository uploads, WorkspaceRepository workspaces) {
        return new UploadAccessGuard(uploads, workspaces);
    }

    @Bean
    public UploadChunkCommitService uploadChunkCommitService(UploadRepository uploads,
                                                             UploadAccessGuard guard) {
        return new UploadChunkCommitService(uploads, guard);
    }

    @Bean
    public UploadService uploadService(WorkspaceRepository workspaces, UploadRepository uploads,
                                       IdempotencyRepository idempotency, TaskRepository tasks,
                                       MediaStorage storage, UploadChunkCommitService chunkCommit,
                                       UploadAccessGuard guard, MediaWorkspaceProperties properties,
                                       Clock clock) {
        return new UploadService(workspaces, uploads, idempotency, tasks, storage, chunkCommit, guard,
                properties, clock);
    }

    @Bean
    public UploadFinalizeTransactionService uploadFinalizeTransactionService(
            UploadRepository uploads, MediaRepository media, TaskRepository tasks,
            CapacityRepository capacity, WorkspaceRepository workspaces, OutboxRepository outbox,
            EventSerializer serializer, MediaWorkspaceProperties properties, Clock clock) {
        return new UploadFinalizeTransactionService(uploads, media, tasks, capacity, workspaces,
                outbox, serializer, properties, clock);
    }

    @Bean
    public UploadFinalizeService uploadFinalizeService(
            UploadRepository uploads, UploadFinalizeTransactionService transactions,
            MediaStorage storage, Clock clock) {
        return new UploadFinalizeService(uploads, transactions, storage, clock);
    }

    @Bean
    public WorkspaceService workspaceService(WorkspaceRepository workspaces, UserRepository users,
                                             MediaWorkspaceProperties properties) {
        return new WorkspaceService(workspaces, users, properties);
    }

    @Bean
    public TaskService taskService(TaskRepository tasks, MediaRepository media,
                                   WorkspaceRepository workspaces, IdempotencyRepository idempotency,
                                   Clock clock) {
        return new TaskService(tasks, media, workspaces, idempotency, clock);
    }

    @Bean
    public MediaService mediaService(MediaRepository media, TaskRepository tasks,
                                     WorkspaceRepository workspaces, TaskService taskService,
                                     Clock clock) {
        return new MediaService(media, tasks, workspaces, taskService, clock);
    }

    @Bean
    public ShareService shareService(ShareRepository shares, ShareSessionRepository shareSessions,
                                     MediaRepository media, WorkspaceRepository workspaces,
                                     SecretTokens tokens, MediaWorkspaceProperties properties,
                                     Clock clock) {
        return new ShareService(shares, shareSessions, media, workspaces, tokens, properties, clock);
    }

    /**
     * The API consumes result events to build its notification and audit projection. It does not
     * consume request events: those belong to the worker, and the API never claims a task.
     */
    @Bean
    public ResultProjectionService resultProjectionService(InboxRepository inbox, AuditRepository audit,
                                                           TaskRepository tasks, MediaRepository media,
                                                           Clock clock) {
        return new ResultProjectionService(inbox, audit, tasks, media, clock);
    }

    @Bean
    public TaskIntakeService taskIntakeService(InboxRepository inbox, TaskRepository tasks, Clock clock) {
        return new TaskIntakeService(inbox, tasks, clock);
    }

    /**
     * The consumer that builds the notification and audit projection from result events.
     *
     * <p>Registered explicitly: {@code @KafkaListener} only reaches classes that are beans, and an
     * unregistered consumer produces no error - just events nobody reads. The API consumes results
     * and never request events; the request consumer belongs to the worker, and
     * {@code ListenerRegistrationTest} asserts that split.
     */
    @Bean
    public ResultEventConsumer resultEventConsumer(ResultProjectionService projection,
                                                   DeadLetterService deadLetters,
                                                   EventSerializer serializer,
                                                   TopicNames topics) {
        return new ResultEventConsumer(projection, deadLetters, serializer, topics);
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
}
