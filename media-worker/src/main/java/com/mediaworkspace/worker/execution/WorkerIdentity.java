package com.mediaworkspace.worker.execution;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Identity of this worker process.
 *
 * <p>Generated once at startup and written to every lease the process takes. It is deliberately a
 * per-process value and not a hostname or a configured name: two workers started from the same
 * directory with the same configuration must still be distinguishable, because the whole publish
 * gate is "the row still says this exact worker owns it".
 *
 * @param instanceId value stored in {@code processing_task.worker_id}
 * @param startedAt  when this process began, useful when reading two workers' logs together
 */
public record WorkerIdentity(String instanceId, java.time.Instant startedAt, String host) {

    public static WorkerIdentity generate() {
        return new WorkerIdentity(UUID.randomUUID().toString(), java.time.Instant.now(), hostName());
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    /**
     * A short form for log lines, so a record can be attributed to one process at a glance while
     * the full identifier stays available for a database lookup.
     */
    public String shortId() {
        return instanceId.substring(0, 8);
    }
}
