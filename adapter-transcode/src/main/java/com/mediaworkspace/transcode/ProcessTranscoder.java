package com.mediaworkspace.transcode;

import com.mediaworkspace.application.port.transcode.CancellationToken;
import com.mediaworkspace.application.port.transcode.ProbeResult;
import com.mediaworkspace.application.port.transcode.ProbeSpec;
import com.mediaworkspace.application.port.transcode.ProgressListener;
import com.mediaworkspace.application.port.transcode.TranscodeException;
import com.mediaworkspace.application.port.transcode.TranscodeResult;
import com.mediaworkspace.application.port.transcode.TranscodeSpec;
import com.mediaworkspace.application.port.transcode.Transcoder;
import com.mediaworkspace.contracts.model.TaskErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs ffprobe and FFmpeg as child processes.
 *
 * <p>Failure classification is the point of this class. A non-zero exit is not reported as one
 * generic error: the exit code, the retained stderr tail and the state of the output files are
 * combined into the code the task state machine acts on. In particular a produced file is only
 * accepted after ffprobe has read it back, because a zero exit code from FFmpeg means the command
 * finished, not that the result is a playable video.
 */
public class ProcessTranscoder implements Transcoder {

    private static final Logger log = LoggerFactory.getLogger(ProcessTranscoder.class);

    private final String ffprobePath;
    private final String ffmpegPath;

    public ProcessTranscoder(String ffprobePath, String ffmpegPath) {
        this.ffprobePath = ffprobePath;
        this.ffmpegPath = ffmpegPath;
    }

    @Override
    public ProbeResult probe(ProbeSpec spec, CancellationToken cancel) {
        List<String> argv = prependExecutable(ffprobePath, FfmpegArguments.probe(spec));
        ProcessRunner.Outcome outcome;
        try {
            outcome = ProcessRunner.run(argv, spec.timeout(), cancel, line -> {
            }, spec.maxStandardOutputBytes(), 8 * 1024);
        } catch (IOException e) {
            throw new TranscodeException(TaskErrorCode.PROCESS_START_FAILED,
                    "ffprobe could not be started: " + e.getMessage(), e);
        }
        if (outcome.cancelled()) {
            throw new TranscodeException(TaskErrorCode.WORKER_LOST,
                    "probe was cancelled", outcome.standardErrorTail(), null);
        }
        if (outcome.timedOut()) {
            throw new TranscodeException(TaskErrorCode.PROCESS_TIMEOUT,
                    "ffprobe exceeded its deadline reading the source", outcome.standardErrorTail(), null);
        }
        if (outcome.exitCode() != 0) {
            // ffprobe refuses anything it cannot demux: a truncated file, a text file renamed to
            // .mp4, an unsupported container.
            throw new TranscodeException(TaskErrorCode.INVALID_MEDIA,
                    "the source is not a readable media container", outcome.standardErrorTail(),
                    outcome.exitCode());
        }
        if (outcome.outputTruncated()) {
            throw new TranscodeException(TaskErrorCode.INVALID_MEDIA,
                    "the probe report exceeded the size limit", null, outcome.exitCode());
        }
        ProbeResult result = ProbeJson.parse(String.join("", outcome.standardOutputLines()))
                .orElseThrow(() -> new TranscodeException(TaskErrorCode.INVALID_MEDIA,
                        "the probe report could not be interpreted", outcome.standardErrorTail(),
                        outcome.exitCode()));
        if (!result.hasVideoStream()) {
            throw new TranscodeException(TaskErrorCode.UNSUPPORTED_MEDIA,
                    "the container has no video stream", null, outcome.exitCode());
        }
        return result;
    }

    @Override
    public TranscodeResult execute(TranscodeSpec spec, ProgressListener progress, CancellationToken cancel) {
        List<String> argv = prependExecutable(ffmpegPath, FfmpegArguments.transcode(spec));
        ProcessRunner.Outcome outcome;
        try {
            outcome = ProcessRunner.run(argv, spec.deadline(), cancel,
                    line -> reportProgress(line, spec.sourceDurationMs(), progress),
                    256 * 1024, spec.keepStandardErrorBytes());
        } catch (IOException e) {
            throw new TranscodeException(TaskErrorCode.PROCESS_START_FAILED,
                    "the encoder could not be started: " + e.getMessage(), e);
        }
        if (outcome.cancelled()) {
            // The caller cancels because the lease was lost or the user cancelled; the partial
            // output is never promoted.
            throw new TranscodeException(TaskErrorCode.WORKER_LOST, "the encode was cancelled",
                    outcome.standardErrorTail(), null);
        }
        if (outcome.timedOut()) {
            throw new TranscodeException(TaskErrorCode.PROCESS_TIMEOUT,
                    "the encode exceeded its deadline", outcome.standardErrorTail(), null);
        }
        if (outcome.exitCode() != 0) {
            throw classifyFailure(outcome, spec);
        }

        // A zero exit is necessary but not sufficient: read the produced files back.
        ProbeResult produced = probe(ProbeSpec.of(spec.outputFile()), CancellationToken.none());
        long outputBytes = sizeOf(spec.outputFile());
        if (outputBytes <= 0 || produced.width() == null || produced.height() == null) {
            throw new TranscodeException(TaskErrorCode.OUTPUT_INVALID,
                    "the encoder produced no usable video", outcome.standardErrorTail(), 0);
        }
        long producedDuration = produced.durationMs() == null ? 0 : produced.durationMs();
        if (spec.isTruncated(producedDuration)) {
            // FFmpeg salvages what it can from a damaged container and still exits zero, so a short
            // result is the only evidence that the source was truncated. Publishing it would drop
            // the user's content without telling anyone.
            throw new TranscodeException(TaskErrorCode.INVALID_MEDIA,
                    "the source is damaged: " + producedDuration + " ms decoded of "
                            + spec.sourceDurationMs() + " ms",
                    outcome.standardErrorTail(), outcome.exitCode());
        }

        double posterAt = FfmpegArguments.posterPositionSeconds(producedDuration);
        extractPoster(spec, posterAt);
        long posterBytes = sizeOf(spec.posterFile());
        if (posterBytes <= 0) {
            throw new TranscodeException(TaskErrorCode.OUTPUT_INVALID,
                    "the poster frame could not be produced", null, 0);
        }
        // The poster is read back too. A file that exists but is not a decodable image would
        // otherwise be published as the media's cover and fail in the browser instead.
        ProbeResult poster = probe(ProbeSpec.of(spec.posterFile()), CancellationToken.none());
        if (!"mjpeg".equals(poster.videoCodec()) && !"png".equals(poster.videoCodec())) {
            throw new TranscodeException(TaskErrorCode.OUTPUT_INVALID,
                    "the poster is not a readable image (codec " + poster.videoCodec() + ")", null, 0);
        }

        return new TranscodeResult(spec.outputFile(), outputBytes, spec.posterFile(), posterBytes,
                producedDuration, produced.width(), produced.height(),
                produced.videoCodec(), produced.audioCodec());
    }

    /**
     * Turns a non-zero exit into the code the state machine acts on.
     *
     * <p>A full disk is recoverable once space is freed; anything else from the encoder is treated as
     * a transient execution problem, because the source already passed probing and a retry is
     * therefore reasonable.
     */
    private TranscodeException classifyFailure(ProcessRunner.Outcome outcome, TranscodeSpec spec) {
        String stderr = outcome.standardErrorTail() == null ? "" : outcome.standardErrorTail();
        // 126 and 127 are the shell's "found but not executable" and "not found". They mean the
        // binary could not be launched at all, which is an environment problem to be retried once
        // the deployment is fixed, not an internal encoder error. A CR in a script's shebang
        // produces exactly this, and reporting it as INTERNAL_ERROR hides the real cause.
        if (outcome.exitCode() == 126 || outcome.exitCode() == 127) {
            return new TranscodeException(TaskErrorCode.PROCESS_START_FAILED,
                    "the encoder binary could not be executed (exit " + outcome.exitCode() + ")",
                    outcome.standardErrorTail(), outcome.exitCode());
        }
        if (stderr.contains("No space left on device") || !hasFreeSpace(spec.outputFile().getParent())) {
            return new TranscodeException(TaskErrorCode.DISK_FULL,
                    "the storage volume ran out of space", outcome.standardErrorTail(), outcome.exitCode());
        }
        return new TranscodeException(TaskErrorCode.INTERNAL_ERROR,
                "the encoder exited with code " + outcome.exitCode(), outcome.standardErrorTail(),
                outcome.exitCode());
    }

    private void extractPoster(TranscodeSpec spec, double atSecond) {
        List<String> argv = prependExecutable(ffmpegPath, FfmpegArguments.poster(spec, atSecond));
        try {
            ProcessRunner.Outcome outcome = ProcessRunner.run(argv, java.time.Duration.ofMinutes(2),
                    CancellationToken.none(), line -> {
                    }, 64 * 1024, 8 * 1024);
            if (outcome.exitCode() != 0) {
                throw new TranscodeException(TaskErrorCode.OUTPUT_INVALID,
                        "the poster frame could not be produced", outcome.standardErrorTail(),
                        outcome.exitCode());
            }
        } catch (IOException e) {
            throw new TranscodeException(TaskErrorCode.PROCESS_START_FAILED,
                    "the poster step could not be started: " + e.getMessage(), e);
        }
    }

    /**
     * Reads {@code -progress} output.
     *
     * <p>The encoder writes {@code key=value} lines. Its output timestamp is compared with the
     * probed source duration; without a positive duration there is no meaningful percentage.
     * Anything unparsable is ignored: a progress line is a hint, and failing the encode over one
     * would be the wrong trade.
     */
    static void reportProgress(String line, long sourceDurationMs, ProgressListener progress) {
        try {
            String[] parts = line.split("=", 2);
            if (parts.length != 2) {
                return;
            }
            String key = parts[0].trim();
            String value = parts[1].trim();
            if (sourceDurationMs > 0 && ("out_time_ms".equals(key) || "out_time_us".equals(key))) {
                // Both keys carry microseconds in FFmpeg's progress output. The name out_time_ms is
                // historical. BigInteger keeps both the percentage numerator and the duration in
                // microseconds exact even when either would overflow a long after multiplication.
                long micros = Long.parseLong(value);
                if (micros < 0) {
                    return;
                }
                BigInteger percent = BigInteger.valueOf(micros).multiply(BigInteger.valueOf(100))
                        .divide(BigInteger.valueOf(sourceDurationMs).multiply(BigInteger.valueOf(1000)));
                progress.onProgress(percent.min(BigInteger.valueOf(99)).intValue());
            }
        } catch (RuntimeException e) {
            // A malformed progress line must not stop the reader.
            log.trace("ignoring progress line: {}", line);
        }
    }

    private long sizeOf(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private boolean hasFreeSpace(Path directory) {
        try {
            if (directory == null || !Files.exists(directory)) {
                return true;
            }
            return Files.getFileStore(directory).getUsableSpace() > 1024 * 1024;
        } catch (IOException e) {
            return true;
        }
    }

    private static List<String> prependExecutable(String executable, List<String> arguments) {
        List<String> argv = new java.util.ArrayList<>(arguments.size() + 1);
        argv.add(executable);
        argv.addAll(arguments);
        return argv;
    }
}
