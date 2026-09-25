package com.mediaworkspace.api.web;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single HTTP byte range.
 *
 * <p>Only the three forms the contract allows are accepted: {@code bytes=start-end},
 * {@code bytes=start-} and {@code bytes=-suffix}. A multi-range request is refused with 416 rather
 * than answered with a multipart response, which is a deliberate product decision recorded in the
 * contract: it keeps the serving path to a single open-ended stream and avoids the boundary parsing
 * that multipart ranges require.
 *
 * <p>Malformed syntax and an unsatisfiable range are distinguished, because they mean different
 * things to a client: a syntax error is a bug in the caller, an unsatisfiable range is a normal
 * outcome when a file changed or a player guessed a length.
 */
public final class ByteRange {

    private static final Pattern SINGLE_RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    /** How the request resolved against the object's length. */
    public sealed interface Resolution {
        /** The whole object is wanted: either no Range header, or one that covers everything. */
        record Whole(long length) implements Resolution {
        }

        /** A satisfiable partial range, inclusive on both ends. */
        record Partial(long start, long endInclusive) implements Resolution {
            public long length() {
                return endInclusive - start + 1;
            }
        }

        /** The range cannot be satisfied against this object; answered with 416. */
        record Unsatisfiable(long length) implements Resolution {
        }
    }

    /** Raised for a syntactically invalid header, which is answered with 400 rather than 416. */
    public static class MalformedRangeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MalformedRangeException(String message) {
            super(message);
        }
    }

    private ByteRange() {
    }

    /**
     * Resolves a Range header against a known length.
     *
     * @param header raw header value, or {@code null} when the client sent none
     * @param length total length of the object
     * @throws MalformedRangeException when the header is present but not a single valid range
     */
    public static Resolution resolve(String header, long length) {
        if (header == null || header.isBlank()) {
            return new Resolution.Whole(length);
        }
        String trimmed = header.trim();
        if (trimmed.contains(",")) {
            // Multiple ranges are a product-level refusal, not a syntax error.
            return new Resolution.Unsatisfiable(length);
        }
        Matcher matcher = SINGLE_RANGE.matcher(trimmed);
        if (!matcher.matches()) {
            throw new MalformedRangeException("only a single bytes range is supported");
        }
        String startText = matcher.group(1);
        String endText = matcher.group(2);

        if (startText.isEmpty() && endText.isEmpty()) {
            throw new MalformedRangeException("the range has neither a start nor a suffix length");
        }
        if (length <= 0) {
            return new Resolution.Unsatisfiable(length);
        }

        if (startText.isEmpty()) {
            // bytes=-N : the last N bytes.
            long suffix = parseLong(endText);
            if (suffix == 0) {
                return new Resolution.Unsatisfiable(length);
            }
            long start = Math.max(0, length - suffix);
            return new Resolution.Partial(start, length - 1);
        }

        long start = parseLong(startText);
        long end = endText.isEmpty() ? length - 1 : parseLong(endText);
        if (start > end || start >= length) {
            return new Resolution.Unsatisfiable(length);
        }
        // A range that extends past the end is clamped, not rejected.
        return new Resolution.Partial(start, Math.min(end, length - 1));
    }

    /**
     * The value of {@code Content-Range} for an unsatisfiable request.
     *
     * <p>{@code bytes}{@code  *}{@code /total} tells a client the object's size so it can retry with
     * a range that fits.
     */
    public static String unsatisfiedContentRange(long length) {
        return "bytes */" + length;
    }

    /** The value of {@code Content-Range} for a partial response. */
    public static String contentRange(long start, long endInclusive, long length) {
        return "bytes " + start + "-" + endInclusive + "/" + length;
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new MalformedRangeException("the range bound is not a number: " + text);
        }
    }

    /** Convenience for callers that want an optional raw header. */
    public static Resolution resolve(Optional<String> header, long length) {
        return resolve(header.orElse(null), length);
    }
}
