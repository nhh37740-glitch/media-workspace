package com.mediaworkspace.contracts.dto;

/**
 * Safe description of a shared asset, returned after a valid token exchange.
 *
 * <p>Contains only what a share holder may see. It deliberately omits the space, the uploader,
 * the storage keys, task state and any list capability.
 *
 * @param title      media title
 * @param durationMs media duration once known, otherwise {@code null}
 * @param expiresAt  expiry instant of the share, UTC ISO-8601
 */
public record ShareAccessView(String title, Long durationMs, String expiresAt) {
}
