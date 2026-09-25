package com.mediaworkspace.contracts.dto;

/**
 * One share of a media asset. The list endpoint never returns the raw token.
 *
 * @param shareId   identifier usable for revocation
 * @param expiresAt expiry instant, UTC ISO-8601
 * @param revokedAt revocation instant, or {@code null} while the share is active
 */
public record ShareListItemView(String shareId, String expiresAt, String revokedAt) {
}
