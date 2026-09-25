package com.mediaworkspace.application.port.transcode;

import com.mediaworkspace.contracts.model.TaskErrorCode;

/**
 * Failure raised by the transcoding adapter, already classified into a stable task error code.
 *
 * <p>{@code standardErrorTail} is a bounded, sanitized excerpt for the attempt record. It is
 * truncated by the adapter and never contains the full stderr of a long encode.
 */
public class TranscodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final TaskErrorCode errorCode;
    private final transient String standardErrorTail;
    private final Integer exitCode;

    public TranscodeException(TaskErrorCode errorCode, String message, String standardErrorTail, Integer exitCode) {
        super(message);
        this.errorCode = errorCode;
        this.standardErrorTail = standardErrorTail;
        this.exitCode = exitCode;
    }

    public TranscodeException(TaskErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.standardErrorTail = null;
        this.exitCode = null;
    }

    public TaskErrorCode errorCode() {
        return errorCode;
    }

    public String standardErrorTail() {
        return standardErrorTail;
    }

    public Integer exitCode() {
        return exitCode;
    }
}
