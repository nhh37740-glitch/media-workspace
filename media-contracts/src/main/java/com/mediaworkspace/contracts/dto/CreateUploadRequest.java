package com.mediaworkspace.contracts.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /spaces/{spaceId}/uploads}.
 *
 * @param filename  display name only; never joined into a server path
 * @param sizeBytes declared size of the original file, at most 1 GiB by default
 * @param sha256    lowercase hex SHA-256 of the whole file, verified after the merge
 * @param title     user-facing title
 */
public record CreateUploadRequest(
        @NotBlank @Size(min = 1, max = 200) String filename,
        @NotNull @Min(1) @Max(1073741824L) Long sizeBytes,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "sha256 must be 64 lowercase hex characters")
        String sha256,
        @NotBlank @Size(min = 1, max = 120) String title) {
}
