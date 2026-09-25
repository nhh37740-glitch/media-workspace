package com.mediaworkspace.application.port.security;

/**
 * Password hashing.
 *
 * <p>A port rather than a direct dependency so the application layer does not depend on a security
 * framework. The implementation in the API application uses BCrypt; hashes and plaintext passwords
 * never appear in logs, error bodies or events.
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String storedHash);
}
