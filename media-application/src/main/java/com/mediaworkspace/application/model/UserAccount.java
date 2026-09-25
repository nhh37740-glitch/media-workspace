package com.mediaworkspace.application.model;

/**
 * A user row as the application layer needs it.
 *
 * <p>{@code passwordHash} never leaves the authentication use case and is never logged.
 */
public record UserAccount(String id, String username, String passwordHash, boolean enabled) {
}
