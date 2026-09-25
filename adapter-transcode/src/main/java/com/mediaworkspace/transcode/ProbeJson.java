package com.mediaworkspace.transcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediaworkspace.application.port.transcode.ProbeResult;

import java.util.Optional;

/**
 * Reads ffprobe's JSON report.
 *
 * <p>Kept separate from process handling so the parsing rules can be tested against captured
 * ffprobe output without running the binary. The container a file actually contains is taken from
 * ffprobe, never from the file extension.
 */
public final class ProbeJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProbeJson() {
    }

    /**
     * Parses a report.
     *
     * @return the description, or empty when the text is not a usable ffprobe report
     */
    public static Optional<ProbeResult> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode format = root.path("format");
            JsonNode streams = root.path("streams");
            if (!streams.isArray()) {
                return Optional.empty();
            }
            JsonNode video = firstStreamOfType(streams, "video");
            JsonNode audio = firstStreamOfType(streams, "audio");
            return Optional.of(new ProbeResult(
                    format.path("format_name").asText(null),
                    video != null,
                    audio != null,
                    video == null ? null : video.path("codec_name").asText(null),
                    audio == null ? null : audio.path("codec_name").asText(null),
                    durationMillis(format, video),
                    video == null ? null : intOrNull(video, "width"),
                    video == null ? null : intOrNull(video, "height"),
                    longOrNull(format, "bit_rate")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static JsonNode firstStreamOfType(JsonNode streams, String type) {
        for (JsonNode stream : streams) {
            if (type.equals(stream.path("codec_type").asText(null))) {
                return stream;
            }
        }
        return null;
    }

    /**
     * Duration in milliseconds.
     *
     * <p>The container is asked first; the video stream is the fallback because some containers
     * report a duration of zero or omit it while the stream still knows how long it is.
     */
    private static Long durationMillis(JsonNode format, JsonNode video) {
        Long fromFormat = secondsToMillis(format.path("duration").asText(null));
        if (fromFormat != null && fromFormat > 0) {
            return fromFormat;
        }
        return video == null ? fromFormat : secondsToMillis(video.path("duration").asText(null));
    }

    private static Long secondsToMillis(String seconds) {
        if (seconds == null || seconds.isBlank() || "N/A".equals(seconds)) {
            return null;
        }
        try {
            return Math.round(Double.parseDouble(seconds) * 1000.0);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText(""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
