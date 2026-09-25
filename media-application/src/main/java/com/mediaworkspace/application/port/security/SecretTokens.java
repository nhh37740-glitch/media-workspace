package com.mediaworkspace.application.port.security;

/**
 * Generation and hashing of share secrets.
 *
 * <p>The raw token is derived from a cryptographically strong source, is URL-safe, and is returned
 * to the creator exactly once. Only its hash is stored, so a database read cannot reconstruct a
 * working link.
 */
public interface SecretTokens {

    /** Number of random bytes before encoding. */
    int TOKEN_BYTES = 32;

    /** A fresh URL-safe token. */
    String newToken();

    /** Lowercase hex SHA-256 of a token, as stored in the database. */
    String hash(String token);
}
