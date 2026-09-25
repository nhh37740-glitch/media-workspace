package com.mediaworkspace.contracts.model;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stable machine-readable task failure codes.
 *
 * <p>These strings are persisted in {@code processing_task.error_code}, appear in
 * {@code TASK_FAILED.payload.errorCode} and are shown to the user on the task detail page.
 * Whether a code may be retried is a business rule and lives in {@code media-domain}; it is not
 * encoded here.
 */
public enum TaskErrorCode {
    /** ffprobe rejected the container, or it contains no video stream. Permanent. */
    INVALID_MEDIA,
    /** The container or codec is not in the supported set. Permanent. */
    UNSUPPORTED_MEDIA,
    /** The immutable original file is absent or its size changed. Permanent. */
    SOURCE_MISSING,
    /** The OS refused to start ffprobe/FFmpeg. Transient. */
    PROCESS_START_FAILED,
    /** The task exceeded its wall-clock deadline. Transient. */
    PROCESS_TIMEOUT,
    /** The storage volume ran out of space. Transient. */
    DISK_FULL,
    /** The worker lost its lease, or its process disappeared. Transient. */
    WORKER_LOST,
    /** The declared whole-file SHA-256 did not match the merged file. Permanent. */
    HASH_MISMATCH,
    /** A stale execution tried to publish; affected rows were zero. Not retried. */
    STALE_EXECUTION,
    /** ffprobe validation of the produced artifact failed. Transient. */
    OUTPUT_INVALID,
    /** Unexpected server-side failure. Transient. */
    INTERNAL_ERROR;

    private static final Map<String, TaskErrorCode> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    /** Returns the code for a stored value, or {@code null} when the value is not a known code. */
    public static TaskErrorCode fromName(String name) {
        return name == null ? null : BY_NAME.get(name);
    }
}
