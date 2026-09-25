package com.mediaworkspace.application.config;

import java.time.Duration;

/**
 * Tunable limits. Defaults are the demonstration values from the contract; tests use smaller
 * quotas so a case does not have to upload a gigabyte to reach the boundary.
 *
 * @param chunkSizeBytes          size of every chunk except the last
 * @param maxUploadSizeBytes      largest single original file
 * @param workspaceQuotaBytes     source-file quota per workspace
 * @param maxOpenUploadsPerUser   simultaneous non-terminal sessions per user per workspace
 * @param maxUnfinishedTasks      global unfinished-task counter limit
 * @param workerConcurrency       execution slots per worker process
 * @param uploadLeaseDuration     how long a finalizer lease lasts before it must be renewed
 * @param taskLeaseDuration       how long a task lease lasts before it must be renewed
 * @param taskDeadline            wall-clock limit for one execution
 * @param openUploadTtl           how long an untouched OPEN session survives
 * @param gcGracePeriod           how long a file is left alone before the collector may remove it
 * @param maxActiveSharesPerMedia simultaneous live share links per media
 */
public record MediaWorkspaceProperties(
        int chunkSizeBytes,
        long maxUploadSizeBytes,
        long workspaceQuotaBytes,
        int maxOpenUploadsPerUser,
        int maxUnfinishedTasks,
        int workerConcurrency,
        Duration uploadLeaseDuration,
        Duration taskLeaseDuration,
        Duration taskDeadline,
        Duration openUploadTtl,
        Duration gcGracePeriod,
        int maxActiveSharesPerMedia) {

    /** The contract defaults, which the demo profile applies. */
    public static MediaWorkspaceProperties defaults() {
        return new MediaWorkspaceProperties(
                8 * 1024 * 1024,
                1024L * 1024 * 1024,
                10L * 1024 * 1024 * 1024,
                3,
                100,
                2,
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                Duration.ofMinutes(30),
                Duration.ofHours(24),
                Duration.ofHours(24),
                20);
    }
}
