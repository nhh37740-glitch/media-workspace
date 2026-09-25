package com.mediaworkspace.application.port.diagnostics;

import java.util.Map;

/**
 * Structured boundary tracing port.
 *
 * <p>Declared in the application layer so every adapter can report what crossed a boundary without
 * depending on a logging framework. A Logback-backed implementation is wired in the executable
 * applications. This is a searchable correlation protocol for business logs, not a claim that a
 * distributed tracing system is deployed.
 */
public interface BoundaryTrace {

    /** Which side of a boundary is being reported. */
    enum Phase {
        START,
        END,
        ERROR
    }

    /**
     * Reports one boundary event.
     *
     * @param module    owning module name
     * @param operation short operation name
     * @param phase     start, end or error
     * @param fields    additional correlation fields; must not contain secrets, file contents or
     *                  message bodies. Callers pass ids, sizes and hash prefixes only.
     */
    void record(String module, String operation, Phase phase, Map<String, Object> fields);

    /**
     * Reports a boundary failure.
     *
     * <p>The full stack trace is captured once, at the boundary that first converts the throwable
     * into a stable error code. Upper layers propagate the id rather than logging the trace again.
     */
    void recordError(String module, String operation, String errorCode, Throwable cause,
                     Map<String, Object> fields);
}
