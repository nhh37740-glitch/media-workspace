package com.mediaworkspace.transcode;

import com.mediaworkspace.application.port.transcode.CancellationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runs a child process with both output pipes drained, a wall-clock deadline and cooperative
 * cancellation.
 *
 * <p>Three properties matter and are the reason this is not a few lines around
 * {@code ProcessBuilder}:
 * <ul>
 *   <li><b>Both pipes are drained concurrently.</b> A child that fills either pipe buffer blocks
 *       forever, and that is exactly what an encoder does when it reports progress on stdout while
 *       logging warnings on stderr.</li>
 *   <li><b>Neither pipe is retained in full.</b> stdout is streamed to a consumer and stderr keeps
 *       only its tail, so a process that writes a gigabyte cannot grow the worker's heap with it.</li>
 *   <li><b>Termination is scoped to this process tree.</b> The descendants of the process this
 *       runner started are destroyed; no other process on the host is touched, and nothing is
 *       matched by executable name.</li>
 * </ul>
 */
public final class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    /** How long a terminated child is given to exit on its own before it is killed outright. */
    private static final Duration GRACEFUL_EXIT = Duration.ofSeconds(5);

    /** How often the cancellation flag is checked while the child runs. */
    private static final Duration CANCEL_POLL = Duration.ofMillis(200);

    private ProcessRunner() {
    }

    /** What a finished (or terminated) child produced. */
    public record Outcome(
            int exitCode,
            List<String> standardOutputLines,
            String standardErrorTail,
            boolean timedOut,
            boolean cancelled,
            boolean outputTruncated) {

        public boolean exitedNormally() {
            return exitCode == 0 && !timedOut && !cancelled;
        }
    }

    /**
     * Runs a command to completion.
     *
     * @param argv            command and arguments as separate elements; no shell is used
     * @param timeout         wall-clock limit
     * @param cancel          cooperative cancellation, polled while the child runs
     * @param standardOutput  called for each stdout line; used for progress and for JSON reports
     * @param maxStandardOutBytes stop retaining stdout lines after this budget is used; the pipe is
     *                            still drained so the child never blocks
     * @param maxStandardErrorBytes how much of the tail of stderr to retain
     */
    public static Outcome run(List<String> argv, Duration timeout, CancellationToken cancel,
                              Consumer<String> standardOutput, long maxStandardOutBytes,
                              long maxStandardErrorBytes) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(argv);
        // A child that inherits nothing keeps stdin closed, so a tool that would otherwise wait for
        // input sees end-of-stream and continues.
        builder.redirectInput(ProcessBuilder.Redirect.from(devNull()));
        Process process = builder.start();

        AtomicBoolean truncated = new AtomicBoolean(false);
        AtomicLong stdoutBytes = new AtomicLong();
        List<String> stdoutLines = new ArrayList<>();
        Deque<String> stderrTail = new ArrayDeque<>();
        AtomicLong stderrBytes = new AtomicLong();

        ExecutorService drains = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "mw-process-drain");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch finished = new CountDownLatch(2);
        try {
            drains.submit(() -> {
                try {
                    drainStdout(process.getInputStream(), line -> {
                        long used = stdoutBytes.addAndGet(line.length() + 1L);
                        if (used <= maxStandardOutBytes) {
                            synchronized (stdoutLines) {
                                stdoutLines.add(line);
                            }
                        } else {
                            truncated.set(true);
                        }
                        // Retention is bounded, but progress must keep flowing throughout a long
                        // encode even after the retained diagnostic output fills its budget.
                        standardOutput.accept(line);
                    });
                } finally {
                    finished.countDown();
                }
            });
            drains.submit(() -> {
                try {
                    drainStderr(process.getErrorStream(), stderrTail, stderrBytes, maxStandardErrorBytes);
                } finally {
                    finished.countDown();
                }
            });

            boolean timedOut = false;
            boolean cancelled = false;
            long deadline = System.nanoTime() + timeout.toNanos();
            while (process.isAlive()) {
                if (cancel.isCancelled()) {
                    cancelled = true;
                    break;
                }
                if (System.nanoTime() > deadline) {
                    timedOut = true;
                    break;
                }
                if (process.waitFor(CANCEL_POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
            if (timedOut || cancelled) {
                terminateTree(process, cancelled ? "cancellation" : "timeout");
            }

            // Wait for the pipes to close so no line is lost, then collect the exit code.
            finished.await(GRACEFUL_EXIT.toMillis(), TimeUnit.MILLISECONDS);
            int exitCode = process.isAlive() ? -1 : process.exitValue();
            if (process.isAlive()) {
                terminateTree(process, "unclosed pipes");
                exitCode = process.waitFor();
            }
            String stderr = String.join("\n", stderrTail);
            synchronized (stdoutLines) {
                return new Outcome(exitCode, List.copyOf(stdoutLines), stderr, timedOut, cancelled,
                        truncated.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminateTree(process, "interrupt");
            throw new IOException("interrupted while running " + argv.get(0), e);
        } finally {
            drains.shutdownNow();
        }
    }

    private static void drainStdout(InputStream stream, Consumer<String> consumer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    consumer.accept(line);
                } catch (RuntimeException e) {
                    // A malformed progress line is a diagnostic problem, not a reason to stop
                    // reading: abandoning the pipe here would block the encoder instead.
                    log.debug("ignoring an unusable output line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("stdout closed: {}", e.getMessage());
        }
    }

    /**
     * Retains only the tail of stderr.
     *
     * <p>Whole lines are dropped from the front once the budget is exceeded, so the retained text
     * stays readable and its size stays bounded regardless of how much the child writes.
     */
    private static void drainStderr(InputStream stream, Deque<String> tail, AtomicLong retained,
                                    long maxBytes) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int length = line.length() + 1;
                tail.addLast(line);
                retained.addAndGet(length);
                while (retained.get() > maxBytes && !tail.isEmpty()) {
                    retained.addAndGet(-(tail.removeFirst().length() + 1L));
                }
            }
        } catch (IOException e) {
            log.debug("stderr closed: {}", e.getMessage());
        }
    }

    /**
     * Destroys this child and its descendants.
     *
     * <p>Descendants first, so a wrapper process cannot outlive the tree by having already
     * reparented its children.
     */
    static void terminateTree(Process process, String reason) {
        List<ProcessHandle> descendants = process.descendants().toList();
        log.debug("terminating process tree of pid {} ({} descendants) due to {}",
                process.pid(), descendants.size(), reason);
        descendants.forEach(handle -> {
            if (handle.isAlive()) {
                handle.destroy();
            }
        });
        process.destroy();
        try {
            if (!process.waitFor(GRACEFUL_EXIT.toSeconds(), TimeUnit.SECONDS)) {
                descendants.forEach(handle -> {
                    if (handle.isAlive()) {
                        handle.destroyForcibly();
                    }
                });
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static java.io.File devNull() {
        return new java.io.File(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "NUL" : "/dev/null");
    }
}
