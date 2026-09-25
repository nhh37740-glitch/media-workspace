package com.mediaworkspace.application.model;

import java.time.Instant;

/**
 * A browser session created by exchanging a share token.
 *
 * <p>Holding this grants playback of exactly one media until it expires, and nothing else: no
 * space listing, no media detail, no tasks, no original file. The identifier is opaque and is
 * stored in an HttpOnly cookie; the raw share token never appears in a URL path or query.
 *
 * @param id       opaque session identifier held by the cookie
 * @param shareId  the share this session was derived from
 * @param mediaId  the one media this session may play
 * @param expiresAt session expiry; never later than the share's own expiry
 */
public record ShareSession(String id, String shareId, String mediaId, Instant expiresAt) {

    public boolean isActive(Instant now) {
        return now.isBefore(expiresAt);
    }
}
