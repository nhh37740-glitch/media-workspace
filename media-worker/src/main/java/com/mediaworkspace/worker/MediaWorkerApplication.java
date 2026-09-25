package com.mediaworkspace.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Executable entry point of the Worker process.
 *
 * <p>The Worker consumes request events, claims due tasks under a lease, runs ffprobe/FFmpeg in
 * its own attempt directory, and publishes results through the outbox. It exposes no public
 * business port.
 */
@SpringBootApplication
public class MediaWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaWorkerApplication.class, args);
    }
}
