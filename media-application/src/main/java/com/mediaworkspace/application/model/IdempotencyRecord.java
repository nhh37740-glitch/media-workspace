package com.mediaworkspace.application.model;

/**
 * A stored idempotent response.
 *
 * @param requestHash  normalized hash of the request body the response belongs to; a different
 *                     body under the same key is a conflict, not a replay
 * @param responseJson the exact body returned the first time
 * @param httpStatus   the status returned the first time
 */
public record IdempotencyRecord(
        String userId,
        String route,
        String resourceId,
        String keyHash,
        String requestHash,
        String responseJson,
        int httpStatus) {
}
