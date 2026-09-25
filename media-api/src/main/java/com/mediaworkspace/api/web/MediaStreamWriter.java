package com.mediaworkspace.api.web;

import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.port.storage.MediaStorage;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;

/**
 * Serves media bytes with HTTP range support.
 *
 * <p>The file is never read into an array. A range is served by skipping to the start and then
 * copying a bounded number of bytes, so a request for a one-megabyte range of a one-gigabyte video
 * costs one megabyte of transfer and a small fixed buffer.
 *
 * <p>{@code HEAD} is answered by the same method with a null body: the contract requires identical
 * headers without the content, and a client that probes with HEAD before playing would otherwise
 * download the file twice.
 */
@Component
public class MediaStreamWriter {

    private static final int BUFFER_BYTES = 64 * 1024;

    private final MediaStorage storage;

    public MediaStreamWriter(MediaStorage storage) {
        this.storage = storage;
    }

    /**
     * Builds a response for one stored object.
     *
     * @param storageKey   object to serve
     * @param rangeHeader  raw {@code Range} header value, or {@code null}
     * @param contentType  media type to report
     * @param headOnly     whether this is a HEAD request
     */
    public ResponseEntity<StreamingResponseBody> serve(String storageKey, String rangeHeader,
                                                       String contentType, boolean headOnly) {
        long length;
        try {
            if (!storage.exists(storageKey)) {
                throw ApplicationException.notFound("the file is not available", null);
            }
            length = storage.sizeOf(storageKey);
        } catch (MediaStorage.StorageException e) {
            throw new ApplicationException(ApiErrorCode.INTERNAL_ERROR, "the file could not be read");
        }

        ByteRange.Resolution resolution;
        try {
            resolution = ByteRange.resolve(rangeHeader, length);
        } catch (ByteRange.MalformedRangeException e) {
            throw new ApplicationException(ApiErrorCode.BAD_REQUEST, e.getMessage());
        }

        if (resolution instanceof ByteRange.Resolution.Unsatisfiable) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, ByteRange.unsatisfiedContentRange(length))
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        // The bytes behind a key never change: keys are immutable once published, so a client may
        // cache aggressively without ever seeing a stale body under a fresh validator.
        headers.setCacheControl("private, max-age=3600");

        if (resolution instanceof ByteRange.Resolution.Whole whole) {
            headers.setContentLength(whole.length());
            if (headOnly) {
                return ResponseEntity.ok().headers(headers).build();
            }
            return ResponseEntity.ok().headers(headers)
                    .body(output -> copyRange(storageKey, 0, whole.length(), output));
        }

        ByteRange.Resolution.Partial partial = (ByteRange.Resolution.Partial) resolution;
        headers.setContentLength(partial.length());
        headers.set(HttpHeaders.CONTENT_RANGE,
                ByteRange.contentRange(partial.start(), partial.endInclusive(), length));
        if (headOnly) {
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers).build();
        }
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers)
                .body(output -> copyRange(storageKey, partial.start(), partial.length(), output));
    }

    /** Streams exactly {@code count} bytes starting at {@code offset}, never buffering the object. */
    private void copyRange(String storageKey, long offset, long count, java.io.OutputStream output)
            throws IOException {
        try (InputStream in = storage.open(storageKey)) {
            skipFully(in, offset);
            byte[] buffer = new byte[BUFFER_BYTES];
            long remaining = count;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, want);
                if (read == -1) {
                    // The object shrank under us, which can only happen if it was replaced. Stopping
                    // is correct: the response is already committed with a length we cannot fulfil.
                    break;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    /** {@code InputStream.skip} may skip fewer bytes than asked, so the remainder is read off. */
    private void skipFully(InputStream in, long offset) throws IOException {
        long remaining = offset;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    return;
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    /** The media types the platform produces. */
    public static String videoContentType() {
        return "video/mp4";
    }

    public static String posterContentType() {
        return "image/jpeg";
    }
}
