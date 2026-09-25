package com.mediaworkspace.application.model;

import com.mediaworkspace.contracts.model.Role;

/**
 * A workspace membership row.
 *
 * @param userId   member identifier
 * @param username member login name
 * @param role     the member's role
 */
public record Membership(String userId, String username, Role role) {
}
