package com.mediaworkspace.application.error;

import com.mediaworkspace.contracts.error.ApiErrorCode;

import java.util.Map;

/**
 * A failure the application layer can classify.
 *
 * <p>This is the first place an internal exception becomes a stable, client-safe error code. The
 * HTTP adapter maps the code to its status; the full stack trace of the cause is recorded once at
 * this boundary and never travels further.
 */
public class ApplicationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ApiErrorCode code;
    private final String resourceId;
    private final transient Map<String, Object> details;

    public ApplicationException(ApiErrorCode code, String message) {
        this(code, message, null, null, null);
    }

    public ApplicationException(ApiErrorCode code, String message, String resourceId) {
        this(code, message, resourceId, null, null);
    }

    public ApplicationException(ApiErrorCode code, String message, String resourceId,
                                Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.resourceId = resourceId;
        this.details = details;
    }

    public ApiErrorCode code() {
        return code;
    }

    public String resourceId() {
        return resourceId;
    }

    public Map<String, Object> details() {
        return details;
    }

    /** A resource that does not exist, or is not visible to this actor. The two are not told apart. */
    public static ApplicationException notFound(String message, String resourceId) {
        return new ApplicationException(ApiErrorCode.NOT_FOUND, message, resourceId);
    }

    /** The actor is a known member but lacks the role. */
    public static ApplicationException forbidden(String message, String resourceId) {
        return new ApplicationException(ApiErrorCode.FORBIDDEN, message, resourceId);
    }

    /** The resource exists and is visible, but its state forbids the action. */
    public static ApplicationException conflict(String message, String resourceId) {
        return new ApplicationException(ApiErrorCode.STATE_CONFLICT, message, resourceId);
    }

    public static ApplicationException validation(String message, Map<String, Object> details) {
        return new ApplicationException(ApiErrorCode.VALIDATION_FAILED, message, null, details, null);
    }
}
