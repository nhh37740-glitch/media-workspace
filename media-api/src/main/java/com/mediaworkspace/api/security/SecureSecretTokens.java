package com.mediaworkspace.api.security;

import com.mediaworkspace.application.port.security.SecretTokens;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Share tokens from a cryptographically strong source.
 *
 * <p>Thirty-two random bytes, URL-safe encoded without padding. The token is returned to its creator
 * exactly once; only its SHA-256 is stored, so reading the database does not yield a working link,
 * and a link cannot be derived from a share id.
 */
public class SecureSecretTokens implements SecretTokens {

    private final SecureRandom random = new SecureRandom();

    @Override
    public String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
