package com.mediaworkspace.transcode;

import com.mediaworkspace.application.port.transcode.ProbeSpec;
import com.mediaworkspace.application.port.transcode.TranscodeSpec;
import com.mediaworkspace.contracts.model.TranscodePreset;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the argument lists for ffprobe and FFmpeg.
 *
 * <p>Pure functions, so the exact command line can be asserted in a unit test without starting a
 * process. Every argument is a separate list element and the process is started with that list
 * directly: no shell is involved, so a quote, a space or a semicolon in a file name is data rather
 * than syntax, and no path from a request is ever interpolated into a command string.
 */
public final class FfmpegArguments {

    private FfmpegArguments() {
    }

    /**
     * ffprobe invocation.
     *
     * <p>{@code -v error} keeps the output to machine-readable JSON plus real errors, and the
     * explicit {@code -show_entries} list avoids dumping per-frame data for a large file.
     */
    public static List<String> probe(ProbeSpec spec) {
        List<String> args = new ArrayList<>();
        args.add("-hide_banner");
        args.add("-v");
        args.add("error");
        args.add("-print_format");
        args.add("json");
        args.add("-show_format");
        args.add("-show_streams");
        args.add("-i");
        args.add(spec.input().toString());
        return args;
    }

    /**
     * The scale expression for the 720p preset.
     *
     * <p>The box is {@code min(1280, iw)} by {@code min(720, ih)}, not a fixed 1280x720. A fixed box
     * combined with {@code force_original_aspect_ratio=decrease} only avoids distortion; it does not
     * avoid enlargement, because a source smaller than the box fits inside it and is scaled up. A
     * 360x640 clip was measured being blown up to 406x720 by the fixed form. Capping the box at the
     * source size makes "never larger than the source" a property of the expression itself, and
     * {@code force_divisible_by=2} keeps both dimensions even as H.264 with yuv420p requires.
     */
    static final String SCALE_FILTER =
            "scale='min(1280,iw)':'min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2";

    /**
     * FFmpeg invocation for the MP4_720P_V1 preset.
     *
     * <p>{@code -progress pipe:1} puts machine-readable progress on stdout; stderr stays free for
     * diagnostics. The output is written to the per-execution path given in the spec, never to a
     * shared name.
     */
    public static List<String> transcode(TranscodeSpec spec) {
        if (spec.preset() != TranscodePreset.MP4_720P_V1) {
            throw new IllegalArgumentException("unsupported preset: " + spec.preset());
        }
        List<String> args = new ArrayList<>();
        args.add("-hide_banner");
        args.add("-nostdin");
        args.add("-loglevel");
        args.add("warning");
        args.add("-progress");
        args.add("pipe:1");
        args.add("-nostats");
        args.add("-y");
        args.add("-threads");
        args.add(Integer.toString(spec.maxCpuThreads()));
        args.add("-i");
        args.add(spec.input().toString());

        args.add("-map");
        args.add("0:v:0");
        // The trailing '?' makes the audio stream optional, so a silent source is encoded rather
        // than rejected.
        args.add("-map");
        args.add("0:a:0?");

        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add("veryfast");
        args.add("-crf");
        args.add("23");
        args.add("-pix_fmt");
        args.add("yuv420p");
        args.add("-vf");
        args.add(SCALE_FILTER);

        args.add("-c:a");
        args.add("aac");
        args.add("-b:a");
        args.add("128k");
        args.add("-ac");
        args.add("2");

        args.add("-movflags");
        args.add("+faststart");
        args.add("-f");
        args.add("mp4");
        args.add(spec.outputFile().toString());
        return args;
    }

    /**
     * Poster extraction.
     *
     * @param atSecond the second to sample; a fixed 1 second would fail on a very short clip, so the
     *                 caller passes a position it derived from the media duration
     */
    public static List<String> poster(TranscodeSpec spec, double atSecond) {
        List<String> args = new ArrayList<>();
        args.add("-hide_banner");
        args.add("-nostdin");
        args.add("-loglevel");
        args.add("error");
        args.add("-y");
        args.add("-ss");
        args.add(String.format(java.util.Locale.ROOT, "%.3f", Math.max(0, atSecond)));
        args.add("-i");
        args.add(spec.outputFile().toString());
        args.add("-frames:v");
        args.add("1");
        args.add("-q:v");
        args.add("3");
        args.add("-f");
        args.add("image2");
        args.add(spec.posterFile().toString());
        return args;
    }

    /**
     * Chooses where to take the poster from.
     *
     * <p>One second in, unless the clip is shorter than that, in which case the first frame is used
     * because a seek past the end produces no frame at all.
     */
    public static double posterPositionSeconds(long durationMs) {
        return durationMs >= 2000 ? 1.0 : 0.0;
    }
}
