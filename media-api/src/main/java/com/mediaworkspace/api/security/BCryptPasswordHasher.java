package com.mediaworkspace.api.security;

import com.mediaworkspace.application.port.security.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt password hashing.
 *
 * <p>Strength 10 keeps a hash well under the {@code VARCHAR(100)} the schema allows and stays
 * affordable on a two-core host where a login also competes with media work. The plaintext is never
 * stored, never logged and never included in an error body.
 */
public class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return encoder.matches(rawPassword, storedHash);
        } catch (IllegalArgumentException e) {
            // A malformed stored hash is a data problem, not a matching password.
            return false;
        }
    }
}
