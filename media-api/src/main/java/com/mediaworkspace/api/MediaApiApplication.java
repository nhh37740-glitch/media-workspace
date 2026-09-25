package com.mediaworkspace.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Executable entry point of the API process.
 *
 * <p>The API serves HTTP/SSE, owns HTTP and session security, schedules upload finalization, and
 * publishes outbox events. It never executes FFmpeg.
 */
@SpringBootApplication
public class MediaApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaApiApplication.class, args);
    }
}
