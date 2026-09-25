package com.mediaworkspace.application.port.repository;

/**
 * Append-only audit projection.
 *
 * <p>The API builds it by consuming result events. {@code sourceEventId} is unique, so consuming
 * the same event twice appends one row; the projection never applies a state change to the
 * authoritative task table.
 */
public interface AuditRepository {

    /**
     * Appends an audit entry derived from an event.
     *
     * @param sourceEventId the event this entry was derived from; a repeat is ignored
     * @return {@code true} when a row was inserted
     */
    boolean appendFromEvent(String workspaceId, String actorId, String action, String resourceId,
                            String requestId, String detailJson, String sourceEventId);
}
