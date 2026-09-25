package com.mediaworkspace.contracts.event;

/**
 * Topic and consumer-group names.
 *
 * <p>Topic names carry the logical version in the suffix. A prefix may be configured per
 * environment so one broker can host the demo and several isolated test runs without sharing
 * offsets; the prefix is applied by the messaging adapter, not by callers of this class.
 */
public final class EventTopics {

    /** Requests consumed by the worker; 3 partitions in the demo topology. */
    public static final String TASK_REQUESTED = "media.task.requested.v1";
    /** Results consumed by the API for notification and audit projection; 3 partitions. */
    public static final String TASK_RESULT = "media.task.result.v1";
    /** Poison records; 1 partition. */
    public static final String EVENTS_DLQ = "media.events.dlq.v1";

    /** Consumer group of the worker processes. */
    public static final String WORKER_GROUP = "media-worker-v1";
    /** Consumer group of the API notification projection. */
    public static final String API_NOTIFY_GROUP = "media-api-notify-v1";

    /** Retention applied when the adapter creates topics explicitly. */
    public static final long RETENTION_MS = 7L * 24 * 60 * 60 * 1000;

    private EventTopics() {
    }
}
