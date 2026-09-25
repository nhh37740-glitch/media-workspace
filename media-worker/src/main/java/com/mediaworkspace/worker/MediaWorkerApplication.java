package com.mediaworkspace.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Executable entry point of the Worker process.
 *
 * <p>The scan covers {@code com.mediaworkspace} so the persistence, messaging, storage and
 * transcoding configuration classes are picked up. Nothing from the API module is on this
 * classpath - the worker does not depend on it - so the shared scan root cannot start a web
 * container here.
 *
 * <p>The worker consumes request events, claims due tasks under a lease, runs ffprobe and FFmpeg in
 * an execution-private directory and publishes results through the outbox. It exposes no public
 * business port; its only listening socket is the actuator health endpoint on the loopback address.
 */
@SpringBootApplication(scanBasePackages = "com.mediaworkspace")
public class MediaWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaWorkerApplication.class, args);
    }
}
