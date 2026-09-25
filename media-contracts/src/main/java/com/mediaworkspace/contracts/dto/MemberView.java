package com.mediaworkspace.contracts.dto;

/**
 * A workspace member.
 *
 * @param userId   member identifier
 * @param username member login name
 * @param role     OWNER, EDITOR or VIEWER
 */
public record MemberView(String userId, String username, String role) {
}
