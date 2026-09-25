package com.mediaworkspace.contracts.dto;

/**
 * Identity of the signed-in user.
 *
 * @param userId   user identifier
 * @param username login name
 */
public record UserView(String userId, String username) {
}
