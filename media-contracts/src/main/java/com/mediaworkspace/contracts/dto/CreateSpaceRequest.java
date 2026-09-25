package com.mediaworkspace.contracts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /spaces}. The caller becomes the single OWNER of the new space. */
public record CreateSpaceRequest(
        @NotBlank @Size(min = 1, max = 80) String name) {
}
