package com.mediaworkspace.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.service.TaskService;
import com.mediaworkspace.application.support.CanonicalJson;
import com.mediaworkspace.application.support.IdempotencyKeys;
import com.mediaworkspace.contracts.dto.CancelTaskResponse;
import com.mediaworkspace.contracts.dto.PageResponse;
import com.mediaworkspace.contracts.dto.RetryTaskResponse;
import com.mediaworkspace.contracts.dto.TaskAttemptView;
import com.mediaworkspace.contracts.dto.TaskView;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.contracts.trace.TraceFields;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Task state, history, cancellation, retry and the event stream.
 *
 * <p>{@code GET /tasks/{id}} is the authoritative answer. Everything pushed over SSE is a hint, and
 * a client that wants to be sure confirms here - which is what keeps a dropped connection from
 * looking like a task failure.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService tasks;
    private final TaskEventStream eventStream;
    private final CurrentUser currentUser;
    private final RequestContext requestContext;
    private final ObjectMapper objectMapper;

    public TaskController(TaskService tasks, TaskEventStream eventStream, CurrentUser currentUser,
                          RequestContext requestContext, ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.eventStream = eventStream;
        this.currentUser = currentUser;
        this.requestContext = requestContext;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable String taskId) {
        return tasks.get(currentUser.requireId(), taskId);
    }

    @GetMapping("/{taskId}/attempts")
    public PageResponse<TaskAttemptView> attempts(
            @PathVariable String taskId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        return tasks.attempts(currentUser.requireId(), taskId, page, pageSize);
    }

    /**
     * Cancels a task.
     *
     * <p>Answered with 200 even when the task was already cancelled, because the caller's intent is
     * satisfied. A task that already reached SUCCEEDED or FAILED is a conflict: that outcome is
     * decided and cannot be undone.
     */
    @PostMapping("/{taskId}/cancel")
    public CancelTaskResponse cancel(@PathVariable String taskId,
                                     @RequestBody(required = false) JsonNode body) {
        return tasks.cancel(currentUser.requireId(), taskId);
    }

    /**
     * Starts a new generation of a failed or cancelled task.
     *
     * <p>Idempotent under a key: without one, a retried HTTP request would burn a generation and
     * start a second worker execution for an action the user performed once.
     */
    @PostMapping("/{taskId}/retry")
    public ResponseEntity<RetryTaskResponse> retry(
            @PathVariable String taskId,
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(name = TraceFields.IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
        if (idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new ApplicationException(ApiErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be 8..128 characters");
        }
        String requestHash = IdempotencyKeys.sha256Hex(
                CanonicalJson.canonicalize(objectMapper.valueToTree(body == null ? "{}" : body)));
        RetryTaskResponse response = tasks.retry(currentUser.requireId(), taskId, idempotencyKey,
                requestHash, UUID.randomUUID().toString());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Streams task changes.
     *
     * <p>A caller who may not read the task gets the same answer as one asking for a task that does
     * not exist, so the endpoint cannot be used to discover which task ids are real.
     */
    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String taskId) {
        String userId = currentUser.requireId();
        return eventStream.subscribe(userId, taskId)
                .map(emitter -> ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        // Nginx buffers proxied responses by default, which would hold these frames
                        // until the buffer filled and defeat the point of streaming.
                        .header("X-Accel-Buffering", "no")
                        .body(emitter))
                .orElseThrow(() -> ApplicationException.notFound("task not found", taskId));
    }
}
