package com.mediaworkspace.application.port.repository;

import com.mediaworkspace.application.model.IdempotencyRecord;

import java.util.Optional;

/**
 * Stored responses for requests that carry an idempotency key.
 *
 * <p>The scope is user, route, resource and key. The stored request hash covers a canonical
 * serialization of the body, so a replay with the same body returns the original response while a
 * different body under the same key is a conflict.
 */
public interface IdempotencyRepository {

    /** Sentinel used in place of a null resource id so the primary key stays unique. */
    String NO_RESOURCE = "-";

    Optional<IdempotencyRecord> find(String userId, String route, String resourceId, String keyHash);

    /**
     * Stores a response.
     *
     * <p>A concurrent duplicate surfaces as a unique-key violation, which the caller handles by
     * re-reading and returning the winner's response rather than retrying the business operation.
     */
    void insert(IdempotencyRecord record);
}
