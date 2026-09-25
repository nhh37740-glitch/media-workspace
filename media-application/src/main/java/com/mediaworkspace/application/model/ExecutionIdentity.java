package com.mediaworkspace.application.model;

/**
 * The four values that together identify one execution of one task.
 *
 * <p>A publish, progress or failure update is only allowed to take effect while all four still
 * match the row. A cancelled task, a retried generation or a re-leased epoch all change at least
 * one of them, which is what makes a late write from a superseded execution a no-op.
 *
 * @param taskId         task being executed
 * @param generation     generation the update belongs to
 * @param executionEpoch epoch the worker was granted when it claimed the task
 * @param workerId       instance id of the claiming worker; {@code null} when the updater is not
 *                       a worker (the user-facing cancel/retry paths)
 */
public record ExecutionIdentity(String taskId, int generation, long executionEpoch, String workerId) {
}
