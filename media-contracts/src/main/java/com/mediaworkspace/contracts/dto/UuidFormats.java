package com.mediaworkspace.contracts.dto;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Canonical identifier form used on the wire, in the database and in events: lowercase,
 * hyphenated RFC 4122 form.
 */
public final class UuidFormats {

    /** Matches the canonical lowercase hyphenated form only. */
    public static final Pattern CANONICAL =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private UuidFormats() {
    }

    public static String canonical(UUID id) {
        return id.toString().toLowerCase(Locale.ROOT);
    }

    public static String random() {
        return canonical(UUID.randomUUID());
    }

    public static boolean isCanonical(String value) {
        return value != null && CANONICAL.matcher(value).matches();
    }

    /** Parses a canonical id, returning {@code null} instead of throwing on malformed input. */
    public static UUID parse(String value) {
        if (!isCanonical(value)) {
            return null;
        }
        return UUID.fromString(value);
    }
}
