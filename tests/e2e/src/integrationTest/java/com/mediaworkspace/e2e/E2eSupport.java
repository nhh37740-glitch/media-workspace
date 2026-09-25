package com.mediaworkspace.e2e;

import com.mediaworkspace.application.model.MediaRecord;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.application.port.repository.UploadRepository;
import com.mediaworkspace.application.port.repository.UserRepository;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.contracts.model.MediaState;
import com.mediaworkspace.messaging.JacksonEventSerializer;
import com.mediaworkspace.persistence.mapper.AuditMapper;
import com.mediaworkspace.persistence.mapper.CapacityMapper;
import com.mediaworkspace.persistence.mapper.IdempotencyMapper;
import com.mediaworkspace.persistence.mapper.MediaMapper;
import com.mediaworkspace.persistence.mapper.MessagingMapper;
import com.mediaworkspace.persistence.mapper.ShareMapper;
import com.mediaworkspace.persistence.mapper.TaskMapper;
import com.mediaworkspace.persistence.mapper.UploadMapper;
import com.mediaworkspace.persistence.mapper.UserMapper;
import com.mediaworkspace.persistence.mapper.WorkspaceMapper;
import com.mediaworkspace.persistence.repository.AuditRepositoryAdapter;
import com.mediaworkspace.persistence.repository.CapacityRepositoryAdapter;
import com.mediaworkspace.persistence.repository.IdempotencyRepositoryAdapter;
import com.mediaworkspace.persistence.repository.InboxRepositoryAdapter;
import com.mediaworkspace.persistence.repository.MediaRepositoryAdapter;
import com.mediaworkspace.persistence.repository.OutboxRepositoryAdapter;
import com.mediaworkspace.persistence.repository.ShareRepositoryAdapter;
import com.mediaworkspace.persistence.repository.TaskRepositoryAdapter;
import com.mediaworkspace.persistence.repository.UploadRepositoryAdapter;
import com.mediaworkspace.persistence.repository.UserRepositoryAdapter;
import com.mediaworkspace.persistence.repository.WorkspaceRepositoryAdapter;
import com.mediaworkspace.persistence.testsupport.IntegrationEnvironment;
import com.mediaworkspace.persistence.testsupport.TestDatabase;
import com.mediaworkspace.persistence.testsupport.TestMyBatis;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds the pieces a fault test needs against a schema of its own.
 *
 * <p>The repositories are the real ones, wired to the real migrations, so an assertion made here is
 * about the statements that ship. The class deliberately offers no way to reach into a mapper: a
 * test that used the SQL directly could pass while the path the application takes was broken.
 */
public final class E2eSupport implements AutoCloseable {

    private final TestDatabase database;
    private final SqlSessionFactory sessions;

    public final UserRepository users;
    public final WorkspaceRepository workspaces;
    public final UploadRepository uploads;
    public final MediaRepository media;
    public final TaskRepository tasks;
    public final CapacityRepositoryAdapter capacity;
    public final OutboxRepositoryAdapter outbox;
    public final InboxRepositoryAdapter inbox;
    public final AuditRepositoryAdapter audit;
    public final ShareRepositoryAdapter shares;
    public final IdempotencyRepositoryAdapter idempotency;
    private final TaskMapper taskMapper;
    private final MessagingMapper messagingMapper;

    private E2eSupport(TestDatabase database, SqlSessionFactory sessions) {
        this.database = database;
        this.sessions = sessions;
        this.taskMapper = sessions.openSession(true).getMapper(TaskMapper.class);
        this.messagingMapper = sessions.openSession(true).getMapper(MessagingMapper.class);

        UserMapper userMapper = sessions.openSession(true).getMapper(UserMapper.class);
        WorkspaceMapper workspaceMapper = sessions.openSession(true).getMapper(WorkspaceMapper.class);
        UploadMapper uploadMapper = sessions.openSession(true).getMapper(UploadMapper.class);
        MediaMapper mediaMapper = sessions.openSession(true).getMapper(MediaMapper.class);
        CapacityMapper capacityMapper = sessions.openSession(true).getMapper(CapacityMapper.class);
        ShareMapper shareMapper = sessions.openSession(true).getMapper(ShareMapper.class);
        IdempotencyMapper idempotencyMapper = sessions.openSession(true).getMapper(IdempotencyMapper.class);
        AuditMapper auditMapper = sessions.openSession(true).getMapper(AuditMapper.class);

        this.users = new UserRepositoryAdapter(userMapper);
        this.workspaces = new WorkspaceRepositoryAdapter(workspaceMapper);
        this.uploads = new UploadRepositoryAdapter(uploadMapper);
        this.media = new MediaRepositoryAdapter(mediaMapper);
        this.tasks = new TaskRepositoryAdapter(taskMapper, capacityMapper, messagingMapper,
                new JacksonEventSerializer());
        this.capacity = new CapacityRepositoryAdapter(capacityMapper, taskMapper);
        this.outbox = new OutboxRepositoryAdapter(messagingMapper);
        this.inbox = new InboxRepositoryAdapter(messagingMapper);
        this.audit = new AuditRepositoryAdapter(auditMapper);
        this.shares = new ShareRepositoryAdapter(shareMapper);
        this.idempotency = new IdempotencyRepositoryAdapter(idempotencyMapper);
    }

    /** Creates a schema for this run and wires the repositories to it. */
    public static E2eSupport start(String runTag) {
        TestDatabase database = TestDatabase.start(IntegrationEnvironment.create(runTag));
        SqlSessionFactory sessions = TestMyBatis.autocommit(database.dataSource());
        return new E2eSupport(database, sessions);
    }

    public TestDatabase database() {
        return database;
    }

    /** A session factory for the rare test that needs a transaction of its own. */
    public SqlSessionFactory sessions() {
        return sessions;
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures

    public String newUser(String prefix) {
        String id = UUID.randomUUID().toString();
        sessions.openSession(true).getMapper(UserMapper.class)
                .insert(id, prefix + "-" + id.substring(0, 8), "$2a$10$fixture-not-a-real-hash", true);
        return id;
    }

    public String newWorkspace(String ownerId) {
        return workspaces.insert("space-" + ownerId.substring(0, 6), ownerId, 1L << 40);
    }

    /**
     * Inserts a media row and its task directly.
     *
     * <p>Going through the finalizer would need real chunks and a real merge, which these tests are
     * not about: they start from the state the finalizer produces and assert on what happens next.
     */
    public TaskFixture newTask(String workspaceId, String uploaderId, String title) {
        String mediaId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        media.insert(new MediaRecord(mediaId, workspaceId, uploaderId, title, title + ".mp4",
                "merge/" + mediaId + "/original.bin", 1024, "0".repeat(64), MediaState.PROCESSING,
                0L, null, null, null, null, null, null, now));
        tasks.insert(taskId, mediaId, traceId, "MP4_720P_V1", UUID.randomUUID().toString());
        // The counter is what the finalize path increments; a fixture that inserted a task without
        // it would make the drift check disagree with reality.
        capacity.increment("processing");
        return new TaskFixture(mediaId, taskId, traceId);
    }

    public record TaskFixture(String mediaId, String taskId, String traceId) {
    }

    // ---------------------------------------------------------------------------------------------
    // Inspection

    public int inboxCount(String consumerGroup, String eventId) {
        Integer count = database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM inbox_event WHERE consumer_group = ? AND event_id = ?",
                Integer.class, consumerGroup, eventId);
        return count == null ? 0 : count;
    }

    public int outboxCount() {
        Integer count = database.jdbc().queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class);
        return count == null ? 0 : count;
    }

    public String taskState(String taskId) {
        return database.jdbc().queryForObject(
                "SELECT state FROM processing_task WHERE id = ?", String.class, taskId);
    }

    public long taskVersion(String taskId) {
        Long version = database.jdbc().queryForObject(
                "SELECT version FROM processing_task WHERE id = ?", Long.class, taskId);
        return version == null ? -1 : version;
    }

    public String mediaStatus(String mediaId) {
        return database.jdbc().queryForObject(
                "SELECT status FROM media WHERE id = ?", String.class, mediaId);
    }

    public String mediaOutputKey(String mediaId) {
        return database.jdbc().queryForObject(
                "SELECT output_key FROM media WHERE id = ?", String.class, mediaId);
    }

    public int capacityCount() {
        Integer count = database.jdbc().queryForObject(
                "SELECT active_count FROM capacity_counter WHERE name = 'processing'", Integer.class);
        return count == null ? -1 : count;
    }

    public int poisonCount() {
        Integer count = database.jdbc().queryForObject("SELECT COUNT(*) FROM poison_message", Integer.class);
        return count == null ? 0 : count;
    }

    /** Sets a task's lease into the past, standing in for a worker that stopped renewing. */
    public void expireLease(String taskId) {
        database.jdbc().update(
                "UPDATE processing_task SET lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 SECOND) "
                        + "WHERE id = ?", taskId);
    }

    /** Sets the global task counter, so a test can reach the capacity boundary without 100 tasks. */
    public void setCapacityMax(int max) {
        database.jdbc().update("UPDATE capacity_counter SET max_count = ? WHERE name = 'processing'", max);
    }

    /** Moves a task to QUEUED without waiting for an event, for tests about what happens afterwards. */
    public void queueDirectly(String taskId) {
        taskMapper.markQueued(taskId, 1);
    }

    /** The outbox rows, so a test can assert what was queued for delivery. */
    public java.util.List<java.util.Map<String, Object>> outboxRows() {
        return database.jdbc().queryForList("SELECT topic, state, publish_attempt FROM outbox_event ORDER BY created_at");
    }

    /** Consumes one outbox row and returns its JSON body, standing in for the delivery step. */
    public String claimOneOutboxBody() {
        java.util.List<java.util.Map<String, Object>> rows = database.jdbc().queryForList(
                "SELECT event_id FROM outbox_event WHERE state = 'PENDING' ORDER BY created_at LIMIT 1");
        if (rows.isEmpty()) {
            return null;
        }
        String eventId = String.valueOf(rows.get(0).get("event_id"));
        messagingMapper.claimOutbox("test-claim", 30, java.util.List.of(eventId));
        return database.jdbc().queryForObject(
                "SELECT body FROM outbox_event WHERE event_id = ?", String.class, eventId);
    }

    @Override
    public void close() {
        database.close();
    }
}
