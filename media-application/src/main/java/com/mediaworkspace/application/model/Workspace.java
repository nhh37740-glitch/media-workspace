package com.mediaworkspace.application.model;

import com.mediaworkspace.contracts.model.Role;

/**
 * A workspace plus the role the requesting user holds in it.
 *
 * <p>{@code role} is {@code null} for a non-member; callers must treat that as "not visible".
 *
 * @param quotaBytes           source-file quota for this workspace
 * @param usedSourceBytes      bytes of published originals
 * @param reservedSourceBytes  bytes reserved by open upload sessions
 */
public record Workspace(
        String id,
        String name,
        String ownerId,
        long quotaBytes,
        long usedSourceBytes,
        long reservedSourceBytes,
        Role role) {

    /** Bytes still available for new reservations. */
    public long availableBytes() {
        return Math.max(0, quotaBytes - usedSourceBytes - reservedSourceBytes);
    }

    /** The invariant the DDL also enforces: used + reserved never exceeds the quota. */
    public boolean withinQuota() {
        return usedSourceBytes + reservedSourceBytes <= quotaBytes;
    }
}
