package com.mediaworkspace.contracts.dto;

/**
 * Result of {@code POST /tasks/{id}/retry}.
 *
 * @param taskId     the same task, never a new one
 * @param generation the incremented generation
 * @param state      always {@code WAITING_EVENT}: the request event must be consumed again
 */
public record RetryTaskResponse(String taskId, int generation, String state) {
}
