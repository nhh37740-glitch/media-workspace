package com.mediaworkspace.worker.trace;

import com.mediaworkspace.application.port.diagnostics.BoundaryTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports boundary events from the worker as structured log records.
 *
 * <p>The worker's records are the ones that carry taskId, generation, attempt, executionEpoch and
 * workerId. Those five are what make one attempt distinguishable from the next in a task that has
 * been retried, and what makes it possible to say which worker held a lease at a given moment.
 */
public class Slf4jBoundaryTrace implements BoundaryTrace {

    private static final Logger log = LoggerFactory.getLogger("com.mediaworkspace.boundary");

    @Override
    public void record(String module, String operation, Phase phase, Map<String, Object> fields) {
        log.info("boundary {} {} {} {}", module, operation, phase, render(fields));
    }

    @Override
    public void recordError(String module, String operation, String errorCode, Throwable cause,
                            Map<String, Object> fields) {
        log.error("boundary {} {} {} errorCode={} {}", module, operation, Phase.ERROR, errorCode,
                render(fields), cause);
    }

    /**
     * Renders the correlation fields.
     *
     * <p>Task-scoped values are read from the MDC, which the execution context sets for the whole
     * life of one execution and clears in a {@code finally}. Reading them here rather than from each
     * call site is what guarantees every record of one execution agrees on which execution it was.
     */
    private String render(Map<String, Object> fields) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (String key : new String[] {"traceId", "requestId", "invocationId", "taskId", "mediaId",
                "generation", "attempt", "executionEpoch", "workerId"}) {
            putIfPresent(merged, key, MDC.get(key));
        }
        if (fields != null) {
            fields.forEach((key, value) -> putIfPresent(merged, key, value));
        }
        StringBuilder builder = new StringBuilder();
        merged.forEach((key, value) -> {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(key).append('=').append(value);
        });
        return builder.toString();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
