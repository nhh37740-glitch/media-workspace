package com.mediaworkspace.contracts.dto;

/**
 * Identity of the signed-in user.
 *
 * @param userId   user identifier
 * @param username login name
 */
public record UserView(String userId, String username, boolean guest) {
    /** Existing password-login callers represent a normal account. */
    public UserView(String userId, String username) {
        this(userId, username, false);
    }
}
