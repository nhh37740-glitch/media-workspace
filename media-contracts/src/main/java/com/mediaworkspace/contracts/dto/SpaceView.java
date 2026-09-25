package com.mediaworkspace.contracts.dto;

/**
 * A workspace as seen by a member.
 *
 * @param spaceId identifier
 * @param name    display name
 * @param role    the calling user's role in this space
 */
public record SpaceView(String spaceId, String name, String role) {
}
