package com.mediaworkspace.contracts.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /media/{id}}.
 *
 * @param title   new title
 * @param version version the client last read; a mismatch fails with 409 rather than overwriting
 */
public record PatchMediaRequest(
        @NotBlank @Size(min = 1, max = 120) String title,
        @NotNull @Min(0) Long version) {
}
