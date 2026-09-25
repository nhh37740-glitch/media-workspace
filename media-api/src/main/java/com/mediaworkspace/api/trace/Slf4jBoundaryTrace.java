package com.mediaworkspace.api.trace;

import com.mediaworkspace.application.port.diagnostics.BoundaryTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports boundary events as structured log records.
 *
 * <p>The fields are appended as {@code key=value} pairs rather than interpolated into a sentence, so
 * the same text is readable in a terminal and searchable in the JSON log. Correlation ids are read
 * from the MDC when the caller did not supply them, which is what keeps one HTTP request's records
 * linked without every call site repeating them.
 *
 * <p>Full stack traces are written exactly once, at the boundary that first turns a throwable into a
 * stable error code. Upper layers propagate an id instead of logging the trace again; a trace
 * printed at every layer is how a single failure becomes unreadable.
 */
public class Slf4jBoundaryTrace implements BoundaryTrace {

    private static final Logger log = LoggerFactory.getLogger("com.mediaworkspace.boundary");

    @Override
    public void record(String module, String operation, Phase phase, Map<String, Object> fields) {
        if (!log.isInfoEnabled() && phase != Phase.ERROR) {
            return;
        }
        log.info("boundary {} {} {} {}", module, operation, phase, render(fields));
    }

    @Override
    public void recordError(String module, String operation, String errorCode, Throwable cause,
                            Map<String, Object> fields) {
        log.error("boundary {} {} {} errorCode={} {}", module, operation, Phase.ERROR, errorCode,
                render(fields), cause);
    }

    /** Renders safe values only; a caller must never put a secret or a body in {@code fields}. */
    private String render(Map<String, Object> fields) {
        Map<String, Object> merged = new LinkedHashMap<>();
        putIfPresent(merged, "traceId", MDC.get("traceId"));
        putIfPresent(merged, "requestId", MDC.get("requestId"));
        putIfPresent(merged, "invocationId", MDC.get("invocationId"));
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
