package com.mediaworkspace.transcode;

import com.mediaworkspace.application.port.transcode.CancellationToken;
import com.mediaworkspace.application.port.transcode.ProbeResult;
import com.mediaworkspace.application.port.transcode.ProbeSpec;
import com.mediaworkspace.application.port.transcode.TranscodeException;
import com.mediaworkspace.application.port.transcode.TranscodeResult;
import com.mediaworkspace.application.port.transcode.TranscodeSpec;
import com.mediaworkspace.contracts.model.TaskErrorCode;
import com.mediaworkspace.contracts.model.TranscodePreset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real FFmpeg and ffprobe against generated fixtures, plus the deterministic failure modes a real
 * encoder cannot be made to produce on demand.
 *
 * <p>Every case runs actual binaries. A test that stubbed the process layer would prove nothing about
 * pipe draining, deadline enforcement or process-tree termination, which are the properties under
 * assertion here.
 */
class ProcessTranscoderIT {

    private static Path media;
    private static Path fakeScript;
    private static ProcessTranscoder real;

    @TempDir
    Path work;

    @BeforeAll
    static void prepare() throws IOException {
        media = Paths.get(System.getenv().getOrDefault("MW_TEST_MEDIA",
                "/opt/media-workspace/var/test-media"));
        assumeTrue(Files.isDirectory(media), "test media directory is absent: " + media);
        real = new ProcessTranscoder(
                System.getenv().getOrDefault("MW_FFPROBE", "ffprobe"),
                System.getenv().getOrDefault("MW_FFMPEG", "ffmpeg"));

        Path extracted = Files.createTempFile("fake-ffmpeg", ".sh");
        try (InputStream in = ProcessTranscoderIT.class
                .getResourceAsStream("/fake-transcoder/fake-ffmpeg.sh")) {
            Files.copy(in, extracted, StandardCopyOption.REPLACE_EXISTING);
        }
        extracted.toFile().setExecutable(true);
        fakeScript = extracted;
    }

    private static Path fixture(String name) {
        return media.resolve(name);
    }

    /** Duration ffprobe reports for a fixture, which the spec needs for the truncation check. */
    private static long durationOf(String name) {
        ProbeResult probe = real.probe(ProbeSpec.of(fixture(name)), CancellationToken.none());
        return probe.durationMs() == null ? 0 : probe.durationMs();
    }

    private TranscodeSpec spec(String name) {
        return spec(name, "tiny.mp4");
    }

    private TranscodeSpec spec(String name, String fixtureName) {
        Path directory = work.resolve(name);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new TranscodeSpec(fixture(fixtureName), directory.resolve("output.mp4"),
                directory.resolve("poster.jpg"), TranscodePreset.MP4_720P_V1,
                durationOf(fixtureName), Duration.ofMinutes(5), 2, 64 * 1024);
    }

    @Test
    @DisplayName("PROC-01: a normal clip is probed as h264 and encodes to a playable MP4 plus a poster")
    void transcodesRealMedia() {
        ProbeResult probe = real.probe(ProbeSpec.of(fixture("tiny.mp4")), CancellationToken.none());
        assertThat(probe.hasVideoStream()).isTrue();
        assertThat(probe.videoCodec()).isEqualTo("h264");
        assertThat(probe.width()).isEqualTo(640);
        assertThat(probe.height()).isEqualTo(360);

        TranscodeResult result =
                real.execute(spec("normal"), percent -> {
                }, CancellationToken.none());

        assertThat(result.outputBytes()).isPositive();
        assertThat(result.posterBytes()).isPositive();
        assertThat(result.videoCodec()).isEqualTo("h264");
        // Re-probed rather than trusted: a produced file is accepted only once ffprobe reads it back.
        ProbeResult produced = real.probe(ProbeSpec.of(result.outputFile()), CancellationToken.none());
        assertThat(produced.hasVideoStream()).isTrue();
        assertThat(produced.width()).isLessThanOrEqualTo(1280);
        assertThat(produced.height()).isLessThanOrEqualTo(720);
    }

    @Test
    @DisplayName("PROC-01: a silent clip encodes without an audio stream and does not fail")
    void encodesSilentMedia() {
        TranscodeResult result =
                real.execute(spec("silent", "silent.mp4"), percent -> {
                }, CancellationToken.none());
        assertThat(result.outputBytes()).isPositive();
        assertThat(result.audioCodec()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"portrait.mp4", "hd720.mp4", "over720.mp4"})
    @DisplayName("PROC-01: the output fits inside 720p, keeps its aspect ratio and is never enlarged")
    void neverUpscalesAndFitsWithin720p(String fixtureName) {
        ProbeResult source = real.probe(ProbeSpec.of(fixture(fixtureName)), CancellationToken.none());
        TranscodeResult result =
                real.execute(spec("fit-" + fixtureName, fixtureName), percent -> {
                }, CancellationToken.none());

        assertThat(result.width()).isLessThanOrEqualTo(1280);
        assertThat(result.height()).isLessThanOrEqualTo(720);
        assertThat(result.width())
                .as("a %dx%d source must not be enlarged", source.width(), source.height())
                .isLessThanOrEqualTo(source.width());
        assertThat(result.height()).isLessThanOrEqualTo(source.height());
        assertThat(result.width() % 2).as("H.264 with yuv420p needs an even width").isZero();
        assertThat(result.height() % 2).isZero();
        // The aspect ratio must survive the fit: compare within a half-pixel of rounding.
        double sourceRatio = (double) source.width() / source.height();
        double producedRatio = (double) result.width() / result.height();
        assertThat(Math.abs(sourceRatio - producedRatio)).isLessThan(0.02);
    }

    @Test
    @DisplayName("PROC-02: a file with no video stream is rejected at the probe stage as permanent")
    void rejectsUnreadableContainer() {
        assertThatThrownBy(() -> real.probe(ProbeSpec.of(fixture("fake.mp4")), CancellationToken.none()))
                .isInstanceOf(TranscodeException.class)
                .extracting(e -> ((TranscodeException) e).errorCode())
                .isEqualTo(TaskErrorCode.INVALID_MEDIA);
    }

    @Test
    @DisplayName("PROC-02: a truncated file that FFmpeg can partly decode is rejected, not silently published")
    void rejectsTruncatedSource() {
        // The header of this fixture is intact, so ffprobe reports a full duration and a valid video
        // stream, and FFmpeg exits zero having decoded only the part that survived. Neither check
        // catches it; comparing the produced duration against the source is what does.
        ProbeResult source = real.probe(ProbeSpec.of(fixture("corrupt.mp4")), CancellationToken.none());
        assertThat(source.hasVideoStream()).as("the fixture must look valid at the probe stage").isTrue();

        assertThatThrownBy(() -> real.execute(spec("truncated", "corrupt.mp4"), percent -> {
        }, CancellationToken.none()))
                .isInstanceOf(TranscodeException.class)
                .extracting(e -> ((TranscodeException) e).errorCode())
                .isEqualTo(TaskErrorCode.INVALID_MEDIA);
    }

    @Test
    @DisplayName("PROC-03: a flooding child is drained, its stderr tail is bounded, and it still finishes")
    void drainsWithoutGrowingUnboundedly() throws IOException {
        // The fake writes about 4 MiB to stderr while producing a real artifact, so the case reaches
        // the validation stage rather than being rejected for an unreadable output.
        long before = usedHeapBytes();
        TranscodeResult result = withFake("flood", transcoder ->
                transcoder.execute(spec("flood"), percent -> {
                }, CancellationToken.none()));
        long after = usedHeapBytes();

        assertThat(result.outputBytes()).isPositive();
        assertThat(after - before)
                .as("retaining the whole stderr stream would show up as a much larger heap increase")
                .isLessThan(24L * 1024 * 1024);
    }

    @Test
    @DisplayName("PROC-03: progress lines are parsed and clamped below 100")
    void parsesProgress() throws IOException {
        AtomicInteger highest = new AtomicInteger(-1);
        withFake("progress", transcoder -> transcoder.execute(spec("progress"), percent ->
                highest.accumulateAndGet(percent, Math::max), CancellationToken.none()));

        assertThat(highest.get()).isGreaterThan(0);
        assertThat(highest.get())
                .as("100 is reserved for the publish transaction, so a hint must stay below it")
                .isLessThanOrEqualTo(99);
    }

    @Test
    @DisplayName("PROC-03: long encode progress continues after stdout retention fills and end stays below 100%")
    void scalesProgressBySourceDuration() throws IOException {
        TranscodeSpec original = spec("progress-long");
        TranscodeSpec longSource = new TranscodeSpec(original.input(), original.outputFile(),
                original.posterFile(), original.preset(), 600_000, original.deadline(),
                original.maxCpuThreads(), original.keepStandardErrorBytes());
        List<Integer> reported = new ArrayList<>();

        // The fake copies a short but valid MP4. It must fail the existing truncation check after
        // streaming its progress, so a failed attempt never becomes a successful publication.
        assertThatThrownBy(() -> withFake("progress-long", transcoder ->
                transcoder.execute(longSource, reported::add, CancellationToken.none())))
                .isInstanceOf(TranscodeException.class)
                .extracting(e -> ((TranscodeException) e).errorCode())
                .isEqualTo(TaskErrorCode.INVALID_MEDIA);
        assertThat(reported).containsExactly(16, 50);
    }

    @Test
    @DisplayName("PROC-04: a hanging child is terminated at its deadline and the call returns")
    void terminatesHungChildAtDeadline() throws IOException {
        TranscodeSpec spec = new TranscodeSpec(fixture("tiny.mp4"), work.resolve("hang/o.mp4"),
                work.resolve("hang/p.jpg"), TranscodePreset.MP4_720P_V1, 10_000,
                Duration.ofSeconds(3), 2, 64 * 1024);
        Files.createDirectories(work.resolve("hang"));

        long started = System.nanoTime();
        assertThatThrownBy(() -> withFake("hang", transcoder ->
                transcoder.execute(spec, percent -> {
                }, CancellationToken.none())))
                .isInstanceOf(TranscodeException.class)
                .extracting(e -> ((TranscodeException) e).errorCode())
                .isEqualTo(TaskErrorCode.PROCESS_TIMEOUT);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // Three second deadline plus the five second graceful window, bounded generously.
        assertThat(elapsedMs).isLessThan(20_000);
    }

    @Test
    @DisplayName("PROC-04: cancellation stops the child early and reports the execution as lost")
    void cancellationStopsTheChild() throws IOException {
        TranscodeSpec spec = new TranscodeSpec(fixture("tiny.mp4"), work.resolve("cancel/o.mp4"),
                work.resolve("cancel/p.jpg"), TranscodePreset.MP4_720P_V1, 10_000,
                Duration.ofMinutes(5), 2, 64 * 1024);
        Files.createDirectories(work.resolve("cancel"));

        long deadline = System.currentTimeMillis() + 1500;
        CancellationToken token = () -> System.currentTimeMillis() >= deadline;
        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> withFake("hang", transcoder ->
                transcoder.execute(spec, percent -> {
                }, token)))
                .isInstanceOf(TranscodeException.class)
                .extracting(e -> ((TranscodeException) e).errorCode())
                .isEqualTo(TaskErrorCode.WORKER_LOST);
        assertThat(System.currentTimeMillis() - started).isLessThan(15_000);
    }

    @Test
    @DisplayName("PROC-05 and PROC-06: a partial file or a missing file is never accepted")
    void rejectsIncompleteOutput() throws IOException {
        for (String mode : new String[] {"half", "zero-no-file", "exit-nonzero", "disk-full"}) {
            Path directory = work.resolve("bad-" + mode);
            Files.createDirectories(directory);
            TranscodeSpec spec = new TranscodeSpec(fixture("tiny.mp4"), directory.resolve("output.mp4"),
                    directory.resolve("poster.jpg"), TranscodePreset.MP4_720P_V1, 10_000,
                    Duration.ofMinutes(2), 2, 64 * 1024);

            assertThatThrownBy(() -> withFake(mode, transcoder ->
                    transcoder.execute(spec, percent -> {
                    }, CancellationToken.none())))
                    .as("mode %s", mode)
                    .isInstanceOf(TranscodeException.class);
            assertThat(directory.resolve("poster.jpg"))
                    .as("no poster may be produced for %s", mode)
                    .doesNotExist();
        }
    }

    @Test
    @DisplayName("PROC-04: terminating a child also stops the descendants it spawned")
    void terminatesTheWholeProcessTree() throws IOException {
        TranscodeSpec spec = new TranscodeSpec(fixture("tiny.mp4"), work.resolve("tree/o.mp4"),
                work.resolve("tree/p.jpg"), TranscodePreset.MP4_720P_V1, 10_000,
                Duration.ofSeconds(3), 2, 64 * 1024);
        Files.createDirectories(work.resolve("tree"));

        long before = countProcesses();
        assertThatThrownBy(() -> withFake("spawn-child", transcoder ->
                transcoder.execute(spec, percent -> {
                }, CancellationToken.none())))
                .isInstanceOf(TranscodeException.class);

        // The grandchild sleeps in a loop; if only the direct child were killed it would remain.
        long after = awaitProcessCountAtMost(before, Duration.ofSeconds(10));
        assertThat(after)
                .as("the descendant must not survive the termination of its tree")
                .isLessThanOrEqualTo(before + 1);
    }

    @Test
    @DisplayName("PROC-07: a file name with spaces and shell characters is passed as data, not executed")
    void doesNotInterpretShellCharacters() throws IOException {
        Path hostile = work.resolve("clip; touch pwned && echo $(whoami).mp4");
        Files.copy(fixture("tiny.mp4"), hostile);
        Path marker = work.resolve("pwned");

        ProbeResult probe = real.probe(ProbeSpec.of(hostile), CancellationToken.none());

        assertThat(probe.hasVideoStream()).isTrue();
        assertThat(marker).doesNotExist();
    }

    // ---------------------------------------------------------------------------------------------

    private interface TranscodeCall<T> {
        T run(ProcessTranscoder transcoder) throws IOException;
    }

    /**
     * Runs a call against a fake encoder configured for one failure mode.
     *
     * <p>The mode and the artifact sources are delivered through a generated wrapper. Java cannot
     * mutate its own environment, and rewriting the shared script between cases would make the tests
     * order-dependent. The production adapter needs no test hook for this.
     *
     * <p>The <b>real</b> ffprobe is used even here: the adapter re-probes every artifact it is
     * handed, and pointing that probe at the fake would only ever exercise the rejection path
     * instead of the validation the case is meant to reach.
     */
    private <T> T withFake(String mode, TranscodeCall<T> action) throws IOException {
        Path wrapper = work.resolve("fake-" + mode + ".sh");
        Files.writeString(wrapper, "#!/usr/bin/env bash\n"
                + "FAKE_MODE=" + mode + "\n"
                + "FAKE_COPY_FROM=" + fixture("tiny.mp4").toAbsolutePath() + "\n"
                + "FAKE_COPY_POSTER=" + fixture("poster.jpg").toAbsolutePath() + "\n"
                + "export FAKE_MODE FAKE_COPY_FROM FAKE_COPY_POSTER\n"
                + "exec " + fakeScript.toAbsolutePath() + " \"$@\"\n");
        wrapper.toFile().setExecutable(true);
        ProcessTranscoder transcoder = new ProcessTranscoder(
                System.getenv().getOrDefault("MW_FFPROBE", "ffprobe"), wrapper.toString());
        return action.run(transcoder);
    }

    private long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private long countProcesses() {
        return ProcessHandle.allProcesses().count();
    }

    private long awaitProcessCountAtMost(long limit, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        long current = countProcesses();
        while (current > limit + 1 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            current = countProcesses();
        }
        return current;
    }
}
