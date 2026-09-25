package com.mediaworkspace.contracts.dto;

/**
 * CSRF token handed to the browser before any state-changing call.
 *
 * @param token      the framework-issued token value
 * @param headerName header the client must echo it in
 */
public record CsrfResponse(String token, String headerName) {
}
