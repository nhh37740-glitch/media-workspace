package com.mediaworkspace.contracts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code PUT /spaces/{id}/members/{userId}}.
 *
 * <p>Only {@code EDITOR} and {@code VIEWER} are accepted: the single OWNER cannot be overwritten
 * through this endpoint.
 */
public record UpdateMemberRoleRequest(
        @NotBlank @Pattern(regexp = "EDITOR|VIEWER", message = "role must be EDITOR or VIEWER") String role) {
}
