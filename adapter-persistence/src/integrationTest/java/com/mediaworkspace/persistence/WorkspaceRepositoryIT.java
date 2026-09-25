package com.mediaworkspace.persistence;

import com.mediaworkspace.application.model.Workspace;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.contracts.model.Role;
import com.mediaworkspace.persistence.mapper.UserMapper;
import com.mediaworkspace.persistence.mapper.WorkspaceMapper;
import com.mediaworkspace.persistence.repository.WorkspaceRepositoryAdapter;
import com.mediaworkspace.persistence.testsupport.IntegrationEnvironment;
import com.mediaworkspace.persistence.testsupport.TestDatabase;
import com.mediaworkspace.persistence.testsupport.TestMyBatis;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-MySQL checks for the workspace repository: record mapping, membership rules and the row
 * lock that makes quota reservation atomic.
 */
class WorkspaceRepositoryIT {

    private static TestDatabase database;
    private static SqlSessionFactory sessions;
    private static WorkspaceRepository workspaces;
    private static UserMapper users;

    @BeforeAll
    static void start() {
        database = TestDatabase.start(IntegrationEnvironment.create("wsrepo"));
        sessions = TestMyBatis.autocommit(database.dataSource());
        users = sessions.openSession(true).getMapper(UserMapper.class);
        workspaces = new WorkspaceRepositoryAdapter(sessions.openSession(true).getMapper(WorkspaceMapper.class));
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    private String insertUser(String prefix) {
        String id = UUID.randomUUID().toString();
        users.insert(id, prefix + "-" + id.substring(0, 8), "$2a$10$not-a-real-hash", true);
        return id;
    }

    @Test
    @DisplayName("a workspace round-trips and its creator is its OWNER")
    void createAndReadBack() {
        String owner = insertUser("owner");
        String spaceId = workspaces.insert("space-" + owner.substring(0, 6), owner, 10_000L);

        Workspace workspace = workspaces.findById(spaceId).orElseThrow();
        assertThat(workspace.id()).isEqualTo(spaceId);
        assertThat(workspace.ownerId()).isEqualTo(owner);
        assertThat(workspace.quotaBytes()).isEqualTo(10_000L);
        assertThat(workspace.usedSourceBytes()).isZero();
        assertThat(workspace.reservedSourceBytes()).isZero();
        assertThat(workspaces.roleOf(spaceId, owner)).contains(Role.OWNER);
    }

    @Test
    @DisplayName("a non-member sees nothing, and the guarded read reports absence")
    void nonMemberIsInvisible() {
        String owner = insertUser("owner");
        String outsider = insertUser("outsider");
        String spaceId = workspaces.insert("space", owner, 1_000L);

        assertThat(workspaces.findVisibleToUser(spaceId, outsider)).isEmpty();
        assertThat(workspaces.roleOf(spaceId, outsider)).isEmpty();
        assertThat(workspaces.listForUser(outsider)).isEmpty();
        assertThat(workspaces.listForUser(owner)).hasSize(1);
    }

    @Test
    @DisplayName("membership can be granted and changed, and removal never touches the OWNER row")
    void membershipRules() {
        String owner = insertUser("owner");
        String editor = insertUser("editor");
        String spaceId = workspaces.insert("space", owner, 1_000L);

        workspaces.upsertMember(spaceId, editor, Role.EDITOR);
        assertThat(workspaces.roleOf(spaceId, editor)).contains(Role.EDITOR);

        workspaces.upsertMember(spaceId, editor, Role.VIEWER);
        assertThat(workspaces.roleOf(spaceId, editor)).contains(Role.VIEWER);

        // The owner row is protected by the statement itself, not only by the caller's check.
        workspaces.upsertMember(spaceId, owner, Role.EDITOR);
        assertThat(workspaces.roleOf(spaceId, owner)).contains(Role.OWNER);
        assertThat(workspaces.removeMember(spaceId, owner)).isFalse();
        assertThat(workspaces.roleOf(spaceId, owner)).contains(Role.OWNER);

        assertThat(workspaces.removeMember(spaceId, editor)).isTrue();
        assertThat(workspaces.roleOf(spaceId, editor)).isEmpty();
    }

    @Test
    @DisplayName("the quota counters move as the reservation and publish steps require")
    void quotaCounters() {
        String owner = insertUser("owner");
        String spaceId = workspaces.insert("space", owner, 100L);

        workspaces.addReservedBytes(spaceId, 40);
        assertThat(workspaces.findById(spaceId).orElseThrow().reservedSourceBytes()).isEqualTo(40);
        assertThat(workspaces.findById(spaceId).orElseThrow().availableBytes()).isEqualTo(60);

        workspaces.moveReservedToUsed(spaceId, 40);
        Workspace afterPublish = workspaces.findById(spaceId).orElseThrow();
        assertThat(afterPublish.reservedSourceBytes()).isZero();
        assertThat(afterPublish.usedSourceBytes()).isEqualTo(40);

        workspaces.releaseUsedBytes(spaceId, 40);
        assertThat(workspaces.findById(spaceId).orElseThrow().usedSourceBytes()).isZero();

        // A repeated release must not drive the counter negative; the CHECK constraint would
        // otherwise reject the update and turn a double release into an error at the wrong layer.
        workspaces.releaseReservedBytes(spaceId, 999);
        assertThat(workspaces.findById(spaceId).orElseThrow().reservedSourceBytes()).isZero();
    }

    @Test
    @DisplayName("the workspace row lock serializes concurrent quota checks")
    void lockSerializesConcurrentReservations() throws Exception {
        String owner = insertUser("owner");
        String spaceId = workspaces.insert("space", owner, 100L);

        CountDownLatch bothInsideTransaction = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(
                    () -> reserveIfRoom(spaceId, 80, bothInsideTransaction));
            Future<String> second = pool.submit(
                    () -> reserveIfRoom(spaceId, 80, bothInsideTransaction));
            assertThat(first.get(30, TimeUnit.SECONDS)).isIn("RESERVED", "REJECTED");
            assertThat(second.get(30, TimeUnit.SECONDS)).isIn("RESERVED", "REJECTED");
            assertThat(java.util.Set.of(first.get(), second.get()))
                    .as("exactly one reservation of 80 fits in a quota of 100")
                    .containsExactlyInAnyOrder("RESERVED", "REJECTED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(workspaces.findById(spaceId).orElseThrow().reservedSourceBytes()).isEqualTo(80);
    }

    /**
     * Reserves under the row lock, mirroring what the upload use case does: read the balance with
     * the row locked, decide, then write, all inside one transaction.
     */
    private String reserveIfRoom(String spaceId, long amount, CountDownLatch barrier) throws Exception {
        try (var session = sessions.openSession(false)) {
            WorkspaceRepository repository =
                    new WorkspaceRepositoryAdapter(session.getMapper(WorkspaceMapper.class));
            Workspace locked = repository.lockForReservation(spaceId).orElseThrow();
            barrier.countDown();
            barrier.await(10, TimeUnit.SECONDS);
            if (locked.usedSourceBytes() + locked.reservedSourceBytes() + amount > locked.quotaBytes()) {
                session.rollback();
                return "REJECTED";
            }
            repository.addReservedBytes(spaceId, amount);
            session.commit();
            return "RESERVED";
        }
    }
}
