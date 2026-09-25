package com.mediaworkspace.contracts.model;

/**
 * Server-side encoding presets. The client selects a preset name; it never supplies codec
 * parameters, output paths or filter strings.
 */
public enum TranscodePreset {
    /**
     * H.264 / yuv420p, optional AAC, MP4 with faststart, at most 720p on the long edge, aspect
     * preserved, never upscaled.
     */
    MP4_720P_V1
}
