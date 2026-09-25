package com.mediaworkspace.api.web;

import com.mediaworkspace.api.config.SecurityConfiguration;
import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.model.MediaRecord;
import com.mediaworkspace.application.service.ShareService;
import com.mediaworkspace.contracts.dto.ShareAccessRequest;
import com.mediaworkspace.contracts.dto.ShareAccessView;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Share redemption, playback and revocation.
 *
 * <p>A share grants playback of exactly one media. The token is exchanged once for a server-side
 * session held in an HttpOnly cookie; the token itself never appears in a URL path or a query
 * string, where it would land in access logs and browser history.
 *
 * <p>Every request through this controller re-reads the share, so revoking it stops the next
 * request immediately. Bytes already transferred cannot be recalled, and the contract does not
 * pretend otherwise: what is asserted is that new requests fail.
 */
@RestController
@RequestMapping("/api/v1")
public class ShareController {

    private final ShareService shares;
    private final MediaStreamWriter streamWriter;
    private final CurrentUser currentUser;

    public ShareController(ShareService shares, MediaStreamWriter streamWriter, CurrentUser currentUser) {
        this.shares = shares;
        this.streamWriter = streamWriter;
        this.currentUser = currentUser;
    }

    /**
     * Exchanges a share token for a session cookie.
     *
     * <p>An unknown, revoked, expired or deleted-media token is answered identically, so the
     * endpoint cannot be used to tell a revoked link from one that never existed.
     */
    @PostMapping("/public/share-access")
    public ResponseEntity<ShareAccessView> redeem(@Valid @RequestBody ShareAccessRequest request,
                                                  HttpServletResponse response) {
        ShareService.Redemption redemption = shares.redeem(request.token());
        response.addCookie(shareCookie(redemption.sessionId(),
                Duration.between(Instant.now(), redemption.sessionExpiresAt())));
        return ResponseEntity.ok(redemption.view());
    }

    /**
     * Streams the shared media.
     *
     * <p>Authorized by the share cookie rather than by a sign-in session, and re-checked against the
     * share on every request.
     */
    @GetMapping("/public/share-access/content")
    public ResponseEntity<StreamingResponseBody> content(
            @CookieValue(name = SecurityConfiguration.SHARE_COOKIE, required = false) String sessionId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        MediaRecord record = resolveShared(sessionId);
        return streamWriter.serve(record.outputKey(), range, MediaStreamWriter.videoContentType(), false);
    }

    /** Same headers as the GET, without the body. */
    @RequestMapping(value = "/public/share-access/content",
            method = org.springframework.web.bind.annotation.RequestMethod.HEAD)
    public ResponseEntity<StreamingResponseBody> contentHead(
            @CookieValue(name = SecurityConfiguration.SHARE_COOKIE, required = false) String sessionId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        MediaRecord record = resolveShared(sessionId);
        return streamWriter.serve(record.outputKey(), range, MediaStreamWriter.videoContentType(), true);
    }

    /** Revokes a share. Repeating the call is not an error: the end state is what the caller wanted. */
    @DeleteMapping("/shares/{shareId}")
    public ResponseEntity<Void> revoke(@PathVariable String shareId) {
        shares.revoke(currentUser.requireId(), shareId);
        return ResponseEntity.noContent().build();
    }

    private MediaRecord resolveShared(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw ApplicationException.notFound("share session not found", null);
        }
        MediaRecord record = shares.resolveSession(sessionId);
        if (record.outputKey() == null) {
            throw new ApplicationException(ApiErrorCode.NOT_FOUND, "share session not found");
        }
        return record;
    }

    /**
     * Builds the share cookie.
     *
     * <p>HttpOnly, so a script cannot read it; SameSite=Lax, so a cross-site POST does not carry it;
     * scoped to the public path, so it is not attached to requests for workspace resources.
     */
    private Cookie shareCookie(String sessionId, Duration remaining) {
        Cookie cookie = new Cookie(SecurityConfiguration.SHARE_COOKIE, sessionId);
        cookie.setHttpOnly(true);
        cookie.setPath("/api/v1/public");
        cookie.setSecure(false);
        cookie.setAttribute("SameSite", "Lax");
        long seconds = Math.max(60, remaining.toSeconds());
        cookie.setMaxAge((int) Math.min(Integer.MAX_VALUE, seconds));
        return cookie;
    }
}
