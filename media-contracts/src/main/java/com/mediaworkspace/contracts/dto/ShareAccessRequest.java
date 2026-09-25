package com.mediaworkspace.contracts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /public/share-access}: exchanges a share token for a scoped session. */
public record ShareAccessRequest(@NotBlank @Size(min = 16, max = 128) String token) {
}
