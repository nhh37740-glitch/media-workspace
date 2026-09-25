package com.mediaworkspace.contracts.dto;

/**
 * Result of {@code POST /media/{id}/shares}.
 *
 * <p>{@code token} is the only time the raw share secret is ever returned. The database stores
 * only its hash, so a lost response cannot be recovered; the owner revokes the share and creates
 * a new one instead. The client must not persist the token in a durable browser store.
 *
 * @param shareId   share identifier, usable with the revoke endpoint
 * @param token     URL-safe secret to place in the link fragment
 * @param expiresAt expiry instant, UTC ISO-8601
 */
public record CreateShareResponse(String shareId, String token, String expiresAt) {
}
