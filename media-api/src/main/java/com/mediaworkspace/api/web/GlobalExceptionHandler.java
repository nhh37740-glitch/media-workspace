package com.mediaworkspace.api.web;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.contracts.error.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions into the one error body shape the contract defines.
 *
 * <p>The distinction that matters for the logs: a failure the client caused is answered without a
 * stack trace, because a wrong password or a malformed uuid is not a server incident and a trace
 * for it is noise that hides real ones. An unexpected failure is logged in full, once, and the
 * response says only that something went wrong - the server path, the SQL and the exception type
 * stay out of the body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final RequestContext requestContext;

    public GlobalExceptionHandler(RequestContext requestContext) {
        this.requestContext = requestContext;
    }

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ErrorResponse> handleApplication(ApplicationException e) {
        ApiErrorCode code = e.code();
        // A 4xx is the client's problem; it is recorded at debug so the access pattern is visible
        // without a stack trace per rejected request.
        log.debug("request rejected: {} {} ({})", code, e.getMessage(), e.resourceId());
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, e.getMessage(), requestContext.currentRequestId(),
                        e.resourceId(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                details.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.status(ApiErrorCode.VALIDATION_FAILED.status())
                .body(ErrorResponse.of(ApiErrorCode.VALIDATION_FAILED, "request validation failed",
                        requestContext.currentRequestId(), null, details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        e.getConstraintViolations().forEach(violation ->
                details.put(String.valueOf(violation.getPropertyPath()), violation.getMessage()));
        return ResponseEntity.status(ApiErrorCode.VALIDATION_FAILED.status())
                .body(ErrorResponse.of(ApiErrorCode.VALIDATION_FAILED, "request validation failed",
                        requestContext.currentRequestId(), null, details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        // Unknown members are rejected by the mapper configuration, so this also covers a client
        // sending a field the contract does not define.
        String detail = e.getCause() instanceof InvalidFormatException format
                ? "malformed value for " + format.getPath().stream()
                        .map(reference -> reference.getFieldName()).reduce((a, b) -> a + "." + b).orElse("a field")
                : "the request body could not be read";
        return ResponseEntity.status(ApiErrorCode.BAD_REQUEST.status())
                .body(ErrorResponse.of(ApiErrorCode.BAD_REQUEST, detail,
                        requestContext.currentRequestId()));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleMissingOrMismatched(Exception e) {
        return ResponseEntity.status(ApiErrorCode.BAD_REQUEST.status())
                .body(ErrorResponse.of(ApiErrorCode.BAD_REQUEST, "the request is missing a required part",
                        requestContext.currentRequestId()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405)
                .body(ErrorResponse.of(ApiErrorCode.BAD_REQUEST, "method not allowed",
                        requestContext.currentRequestId()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(415)
                .body(ErrorResponse.of(ApiErrorCode.BAD_REQUEST, "unsupported content type",
                        requestContext.currentRequestId()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(ApiErrorCode.NOT_FOUND.status())
                .body(ErrorResponse.of(ApiErrorCode.NOT_FOUND, "no such route",
                        requestContext.currentRequestId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // Logged in full here and only here; the response carries no internal detail.
        log.error("unhandled failure while serving a request", e);
        return ResponseEntity.status(ApiErrorCode.INTERNAL_ERROR.status())
                .body(ErrorResponse.of(ApiErrorCode.INTERNAL_ERROR,
                        "the request could not be completed", requestContext.currentRequestId()));
    }
}
