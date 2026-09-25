package com.mediaworkspace.contracts.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Uniform error body.
 *
 * <p>{@code details} carries only fields that are safe to show a client: never server paths,
 * stack traces, SQL, credentials or media bytes.
 *
 * @param code       stable machine-readable code
 * @param message    short human-readable summary, safe to display
 * @param requestId  server-generated id of this HTTP request, also sent as {@code X-Request-Id}
 * @param resourceId identifier of the resource the request targeted, or {@code null}
 * @param details    additional safe fields, or {@code null} when there are none
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ErrorResponse(
        String code,
        String message,
        String requestId,
        String resourceId,
        Map<String, Object> details) {

    public static ErrorResponse of(ApiErrorCode code, String message, String requestId, String resourceId) {
        return new ErrorResponse(code.name(), message, requestId, resourceId, null);
    }

    public static ErrorResponse of(ApiErrorCode code, String message, String requestId) {
        return new ErrorResponse(code.name(), message, requestId, null, null);
    }

    public static ErrorResponse of(ApiErrorCode code, String message, String requestId, String resourceId,
                                   Map<String, Object> details) {
        return new ErrorResponse(code.name(), message, requestId, resourceId, details);
    }
}
