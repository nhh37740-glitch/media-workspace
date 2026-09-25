package com.mediaworkspace.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Executable entry point of the API process.
 *
 * <p>The scan covers the whole {@code com.mediaworkspace} package so the persistence, messaging and
 * storage configuration classes are picked up. Each adapter contributes only configuration and
 * beans; the worker's classes are not on this process's classpath at all, since the api module does
 * not depend on it, so a shared scan root cannot accidentally start worker components here.
 *
 * <p>The API serves HTTP and SSE, owns the session and CSRF boundary, schedules upload finalization
 * and outbox delivery. It never executes FFmpeg: no transcoding adapter is on this classpath.
 */
@SpringBootApplication(scanBasePackages = "com.mediaworkspace")
public class MediaApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaApiApplication.class, args);
    }
}
