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
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the pieces a fault test needs against a schema of its own.
 *
 * <p>The repositories are the real ones, wired to the real migrations, so an assertion made here is
 * about the statements that ship. There is deliberately no way to reach a mapper directly: a test
 * that used the SQL itself could pass while the path the application takes was broken.
 *
 * <p>One session is shared by the default repositories, and it is used from the test thread only.
 * A {@code SqlSession} is not thread safe, and the first version of this harness opened a fresh
 * session per mapper and never closed them, which exhausted the pool before a single assertion ran -
 * every test failed with a connection timeout rather than with anything about the system under test.
 * Tests that need genuine concurrency ask for {@link #newRepositories()}, which hands out a bundle
 * bound to its own session and connection; only an actual second connection can take a row lock
 * that the first one has to wait for.
 */
public final class E2eSupport implements AutoCloseable {

    private final TestDatabase database;
    private final SqlSessionFactory sessions;
    private final SqlSession sharedSession;

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
    private final RepositoryBundle shared;

    private E2eSupport(TestDatabase database, SqlSessionFactory sessions, SqlSession sharedSession) {
        this.database = database;
        this.sessions = sessions;
        this.sharedSession = sharedSession;

        RepositoryBundle bundle = new RepositoryBundle(sharedSession);
        this.shared = bundle;
        this.users = bundle.users;
        this.workspaces = bundle.workspaces;
        this.uploads = bundle.uploads;
        this.media = bundle.media;
        this.tasks = bundle.tasks;
        this.capacity = bundle.capacity;
        this.outbox = bundle.outbox;
        this.inbox = bundle.inbox;
        this.audit = bundle.audit;
        this.shares = bundle.shares;
        this.idempotency = bundle.idempotency;
        this.taskMapper = sharedSession.getMapper(TaskMapper.class);
        this.messagingMapper = sharedSession.getMapper(MessagingMapper.class);
    }

    /**
     * A set of repositories bound to one session.
     *
     * <p>Obtained from {@link E2eSupport#newRepositories()} when a test needs a caller that is
     * genuinely independent of the test thread's, which is the situation every lock assertion needs.
     * The caller closes it.
     */
    public static final class RepositoryBundle implements AutoCloseable {
        private final SqlSession session;

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

        private RepositoryBundle(SqlSession session) {
            this.session = session;
            CapacityMapper capacityMapper = session.getMapper(CapacityMapper.class);
            MessagingMapper messagingMapper = session.getMapper(MessagingMapper.class);
            this.users = new UserRepositoryAdapter(session.getMapper(UserMapper.class));
            this.workspaces = new WorkspaceRepositoryAdapter(session.getMapper(WorkspaceMapper.class));
            this.uploads = new UploadRepositoryAdapter(session.getMapper(UploadMapper.class));
            this.media = new MediaRepositoryAdapter(session.getMapper(MediaMapper.class));
            this.tasks = new TaskRepositoryAdapter(session.getMapper(TaskMapper.class), capacityMapper,
                    messagingMapper, new JacksonEventSerializer());
            this.capacity = new CapacityRepositoryAdapter(capacityMapper, session.getMapper(TaskMapper.class));
            this.outbox = new OutboxRepositoryAdapter(messagingMapper);
            this.inbox = new InboxRepositoryAdapter(messagingMapper);
            this.audit = new AuditRepositoryAdapter(session.getMapper(AuditMapper.class));
            this.shares = new ShareRepositoryAdapter(session.getMapper(ShareMapper.class));
            this.idempotency = new IdempotencyRepositoryAdapter(session.getMapper(IdempotencyMapper.class));
        }

        @Override
        public void close() {
            session.close();
        }
    }

    /** Creates a schema for this run and wires the repositories to it. */
    public static E2eSupport start(String runTag) {
        TestDatabase database = TestDatabase.start(IntegrationEnvironment.create(runTag));
        SqlSessionFactory sessions = TestMyBatis.autocommit(database.dataSource());
        return new E2eSupport(database, sessions, sessions.openSession(true));
    }

    /** A bundle on its own session and connection, for a caller that must run concurrently. */
    public RepositoryBundle newRepositories() {
        return new RepositoryBundle(sessions.openSession(true));
    }

    public TestDatabase database() {
        return database;
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures

    public String newUser(String prefix) {
        String id = UUID.randomUUID().toString();
        sharedSession.getMapper(UserMapper.class)
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
        return newTask(shared, workspaceId, uploaderId, title);
    }

    /**
     * Inserts a media row and its task on a caller-supplied session.
     *
     * <p>Used by the concurrency tests, where the insertion has to happen on the same connection as
     * the admission decision under test: the property being asserted is that the counter's row lock
     * is held until that caller's transaction ends, and a shared connection could not show it.
     */
    public TaskFixture newTask(RepositoryBundle bundle, String workspaceId, String uploaderId,
                               String title) {
        String mediaId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        bundle.media.insert(new MediaRecord(mediaId, workspaceId, uploaderId, title, title + ".mp4",
                "merge/" + mediaId + "/original.bin", 1024, "0".repeat(64), MediaState.PROCESSING,
                0L, null, null, null, null, null, null, Instant.now()));
        bundle.tasks.insert(taskId, mediaId, traceId, "MP4_720P_V1", UUID.randomUUID().toString());
        // The counter is what the finalize path increments; a fixture that inserted a task without it
        // would make the drift check disagree with reality.
        bundle.capacity.increment("processing");
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

    public int attemptCount(String taskId) {
        Integer count = database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM task_attempt WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    public String latestAttemptState(String taskId) {
        List<Map<String, Object>> rows = database.jdbc().queryForList(
                "SELECT state FROM task_attempt WHERE task_id = ? ORDER BY attempt DESC LIMIT 1", taskId);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("state"));
    }

    /** Sets a task's lease into the past, standing in for a worker that stopped renewing. */
    public void expireLease(String taskId) {
        database.jdbc().update(
                "UPDATE processing_task SET lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 SECOND) "
                        + "WHERE id = ?", taskId);
    }

    /** Moves a task's next attempt deadline into the past so it is immediately claimable. */
    public void makeClaimableNow(String taskId) {
        database.jdbc().update(
                "UPDATE processing_task SET next_run_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
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

    @Override
    public void close() {
        try {
            sharedSession.close();
        } finally {
            database.close();
        }
    }
}
