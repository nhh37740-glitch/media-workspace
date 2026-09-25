package com.mediaworkspace.contracts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Credentials for {@code POST /auth/login}. */
public record LoginRequest(
        @NotBlank @Size(min = 1, max = 64) String username,
        @NotBlank @Size(min = 1, max = 200) String password) {
}
