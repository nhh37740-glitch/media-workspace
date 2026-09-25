package com.mediaworkspace.contracts.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /media/{id}/shares}.
 *
 * <p>The expiry is validated in the application layer against the allowed window (1 minute to
 * 7 days from now) rather than by an annotation, because the bound is relative to server time.
 *
 * @param expiresAt absolute expiry instant, UTC ISO-8601
 */
public record CreateShareRequest(@NotBlank String expiresAt) {
}
