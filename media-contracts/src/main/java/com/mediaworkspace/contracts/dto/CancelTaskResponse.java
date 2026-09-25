package com.mediaworkspace.contracts.dto;

/**
 * Result of {@code POST /tasks/{id}/cancel}.
 *
 * @param taskId task identifier
 * @param state  always {@code CANCELLED}
 */
public record CancelTaskResponse(String taskId, String state) {
}
