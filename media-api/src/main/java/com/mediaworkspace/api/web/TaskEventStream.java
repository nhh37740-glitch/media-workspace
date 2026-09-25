package com.mediaworkspace.api.web;

import com.mediaworkspace.application.model.MediaRecord;
import com.mediaworkspace.application.model.ProcessingTaskRecord;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.contracts.dto.TaskEventView;
import com.mediaworkspace.contracts.model.Role;
import com.mediaworkspace.domain.access.RolePolicy;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-sent events carrying task state changes.
 *
 * <p>Design decisions the contract fixes, and why they are load-bearing:
 * <ul>
 *   <li><b>The stream is not authoritative.</b> Frames are produced by reading the task row on a
 *       timer, so a duplicate, a delay or a lost frame cannot move the client to a state the
 *       database does not have. A client treats a terminal frame as a hint and confirms with
 *       {@code GET /tasks/{id}}.</li>
 *   <li><b>At most one frame per task per interval.</b> Progress is written by the worker at most
 *       once a second anyway, and coalescing here keeps a slow client from queueing.</li>
 *   <li><b>Membership is re-checked on every poll.</b> A frame carries data the caller may no longer
 *       be allowed to see, so losing access closes the connection rather than merely stopping new
 *       frames.</li>
 *   <li><b>The queue is bounded and a slow client is dropped.</b> An emitter that cannot keep up
 *       would otherwise accumulate frames in the API's heap until the process dies - one stuck
 *       browser tab taking down every other request.</li>
 * </ul>
 */
@Component
public class TaskEventStream {

    private static final Logger log = LoggerFactory.getLogger(TaskEventStream.class);

    /** How long a connection may go without a frame before a heartbeat keeps it alive. */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    /** Frames a single connection may have outstanding before it is considered stuck. */
    private static final int MAX_PENDING_FRAMES = 16;

    /** Connections one user may hold, so a browser cannot open unbounded streams. */
    private static final int MAX_STREAMS_PER_USER = 8;

    private final TaskRepository tasks;
    private final MediaRepository media;
    private final WorkspaceRepository workspaces;
    private final RolePolicy rolePolicy = new RolePolicy();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger activeCount = new AtomicInteger();

    private final long timeoutMillis;

    public TaskEventStream(TaskRepository tasks, MediaRepository media, WorkspaceRepository workspaces,
                           @Value("${mediaworkspace.scheduler.sse-timeout-ms:900000}") long timeoutMillis) {
        this.tasks = tasks;
        this.media = media;
        this.workspaces = workspaces;
        this.timeoutMillis = timeoutMillis;
    }

    /** One open connection. */
    private static final class Subscription {
        private final String id;
        private final String taskId;
        private final String userId;
        private final SseEmitter emitter;
        private volatile long lastSentVersion;
        private volatile Instant lastFrameAt = Instant.now();
        private final AtomicInteger pending = new AtomicInteger();

        private Subscription(String id, String taskId, String userId, SseEmitter emitter) {
            this.id = id;
            this.taskId = taskId;
            this.userId = userId;
            this.emitter = emitter;
        }
    }

    /**
     * Opens a stream for a task the caller may read.
     *
     * @return an emitter the caller returns from its handler, or empty when the caller may not read
     *         the task or already holds too many connections
     */
    public Optional<SseEmitter> subscribe(String userId, String taskId) {
        if (countForUser(userId) >= MAX_STREAMS_PER_USER) {
            return Optional.empty();
        }
        Optional<ProcessingTaskRecord> task = tasks.findById(taskId);
        if (task.isEmpty() || !mayRead(userId, task.get())) {
            return Optional.empty();
        }
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        Subscription subscription = new Subscription(
                taskId + ":" + userId + ":" + System.nanoTime(), taskId, userId, emitter);
        subscriptions.put(subscription.id, subscription);
        activeCount.incrementAndGet();

        emitter.onCompletion(() -> remove(subscription.id));
        emitter.onTimeout(() -> remove(subscription.id));
        emitter.onError(e -> remove(subscription.id));

        try {
            // The first frame is the current state, so a client that connects mid-task - or
            // reconnects after a drop - is immediately consistent instead of waiting for a change
            // that may never come because the task already reached its terminal state.
            sendState(subscription, task.get(), "snapshot");
        } catch (IOException e) {
            remove(subscription.id);
            return Optional.empty();
        }
        return Optional.of(emitter);
    }

    /**
     * Pushes a frame for every subscription whose task changed.
     *
     * <p>A single scheduler drives every connection. One thread reading a few rows per second is
     * cheaper than one thread per browser, and it makes the load bounded by the number of open
     * streams rather than by how many clients happen to be connected.
     */
    @Scheduled(fixedDelayString = "${mediaworkspace.scheduler.sse-poll-interval-ms:1000}")
    public void poll() {
        if (subscriptions.isEmpty()) {
            return;
        }
        for (Subscription subscription : subscriptions.values()) {
            try {
                pollOne(subscription);
            } catch (IOException | RuntimeException e) {
                // A failed send means the client is gone; a failed read means the database is
                // unhappy. Either way this connection cannot be served, and letting the exception
                // escape would skip every other subscriber for this tick.
                log.debug("dropping subscription {} after a poll failure: {}",
                        subscription.id, e.getMessage());
                remove(subscription.id);
            }
        }
    }

    private void pollOne(Subscription subscription) throws IOException {
        Optional<ProcessingTaskRecord> maybeTask = tasks.findById(subscription.taskId);
        if (maybeTask.isEmpty()) {
            subscription.emitter.complete();
            remove(subscription.id);
            return;
        }
        ProcessingTaskRecord task = maybeTask.get();
        if (!mayRead(subscription.userId, task)) {
            // Access was revoked while the stream was open; the connection is closed rather than
            // left open with no further frames, because the client must not keep showing data it
            // may no longer see.
            subscription.emitter.complete();
            remove(subscription.id);
            return;
        }
        if (task.version() > subscription.lastSentVersion) {
            sendState(subscription, task, "state");
            return;
        }
        if (Duration.between(subscription.lastFrameAt, Instant.now()).compareTo(HEARTBEAT_INTERVAL) > 0) {
            sendHeartbeat(subscription);
        }
    }

    private void sendState(Subscription subscription, ProcessingTaskRecord task, String eventName)
            throws IOException {
        if (subscription.pending.get() >= MAX_PENDING_FRAMES) {
            // The client is not draining. Closing is the only safe option: queueing more would grow
            // the heap of a process that other requests depend on.
            log.info("closing a task stream that is not being read (task {})", subscription.taskId);
            subscription.emitter.complete();
            remove(subscription.id);
            return;
        }
        TaskEventView frame = new TaskEventView(task.id(), task.state().name(), task.progress(),
                task.version());
        subscription.pending.incrementAndGet();
        try {
            subscription.emitter.send(SseEmitter.event()
                    .id(Long.toString(task.version()))
                    .name(eventName)
                    .data(frame));
            subscription.lastSentVersion = task.version();
            subscription.lastFrameAt = Instant.now();
        } finally {
            subscription.pending.decrementAndGet();
        }
        if (task.state().isTerminal()) {
            subscription.emitter.complete();
            remove(subscription.id);
        }
    }

    private void sendHeartbeat(Subscription subscription) throws IOException {
        // A comment frame keeps intermediaries from closing an idle connection without inventing a
        // state change the client would have to reason about.
        subscription.emitter.send(SseEmitter.event().comment("heartbeat"));
        subscription.lastFrameAt = Instant.now();
    }

    private boolean mayRead(String userId, ProcessingTaskRecord task) {
        Optional<MediaRecord> record = media.findVisible(task.mediaId());
        if (record.isEmpty()) {
            return false;
        }
        Role role = workspaces.roleOf(record.get().workspaceId(), userId).orElse(null);
        return rolePolicy.isVisible(role);
    }

    private long countForUser(String userId) {
        return subscriptions.values().stream()
                .filter(subscription -> subscription.userId.equals(userId))
                .count();
    }

    private void remove(String id) {
        if (subscriptions.remove(id) != null) {
            activeCount.decrementAndGet();
        }
    }

    /** Open connections, reported through the health projection. */
    public int activeStreams() {
        return activeCount.get();
    }

    /** Closes every connection on shutdown, so no client waits for a frame that will not come. */
    @PreDestroy
    public void closeAll() {
        subscriptions.values().forEach(subscription -> {
            try {
                subscription.emitter.complete();
            } catch (RuntimeException ignored) {
                // The connection is already gone; nothing to do.
            }
        });
        subscriptions.clear();
        activeCount.set(0);
    }
}
