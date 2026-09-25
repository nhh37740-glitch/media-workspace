package com.mediaworkspace.application.service;

import com.mediaworkspace.application.config.MediaWorkspaceProperties;
import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.model.MediaRecord;
import com.mediaworkspace.application.model.ShareLinkRecord;
import com.mediaworkspace.application.model.ShareSession;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.port.repository.ShareRepository;
import com.mediaworkspace.application.port.repository.ShareSessionRepository;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.application.port.security.SecretTokens;
import com.mediaworkspace.contracts.dto.CreateShareResponse;
import com.mediaworkspace.contracts.dto.ShareAccessView;
import com.mediaworkspace.contracts.dto.ShareListItemView;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.contracts.model.MediaState;
import com.mediaworkspace.contracts.model.Role;
import com.mediaworkspace.domain.access.RolePolicy;
import com.mediaworkspace.domain.access.SpaceAction;
import com.mediaworkspace.domain.share.ShareWindow;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Share links: creation, listing, revocation and redemption.
 *
 * <p>A share grants playback of one media and nothing else. The raw token is returned exactly once
 * at creation and only its hash is stored, so a lost response cannot be recovered from the
 * database; the owner revokes the orphan and creates a new one. Redemption exchanges the token for
 * a scoped server-side session, and the share's state is re-checked on every later request so a
 * revocation takes effect immediately even though bytes already sent cannot be recalled.
 */
public class ShareService {

    private final ShareRepository shares;
    private final ShareSessionRepository shareSessions;
    private final MediaRepository media;
    private final WorkspaceRepository workspaces;
    private final SecretTokens tokens;
    private final MediaWorkspaceProperties properties;
    private final RolePolicy rolePolicy = new RolePolicy();
    private final Clock clock;

    public ShareService(ShareRepository shares, ShareSessionRepository shareSessions, MediaRepository media,
                        WorkspaceRepository workspaces, SecretTokens tokens,
                        MediaWorkspaceProperties properties, Clock clock) {
        this.shares = shares;
        this.shareSessions = shareSessions;
        this.media = media;
        this.workspaces = workspaces;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates a share for a ready media.
     *
     * <p>The active-share count is checked while the media row lock is held, so two concurrent
     * creations cannot both take the last slot.
     */
    @Transactional
    public CreateShareResponse create(String actorId, String mediaId, Instant expiresAt) {
        Instant now = clock.instant();
        if (!ShareWindow.isValid(now, expiresAt)) {
            throw ApplicationException.validation("expiry must be between one minute and seven days from now",
                    java.util.Map.of("minSeconds", ShareWindow.MINIMUM.toSeconds(),
                            "maxSeconds", ShareWindow.MAXIMUM.toSeconds()));
        }
        MediaRecord record = media.lockVisible(mediaId)
                .orElseThrow(() -> ApplicationException.notFound("media not found", mediaId));
        Role role = workspaces.roleOf(record.workspaceId(), actorId).orElse(null);
        if (!rolePolicy.isVisible(role)) {
            throw ApplicationException.notFound("media not found", mediaId);
        }
        if (!rolePolicy.allows(role, SpaceAction.SHARE)) {
            throw ApplicationException.forbidden("role does not permit sharing", mediaId);
        }
        if (record.status() != MediaState.READY) {
            throw ApplicationException.conflict("only a ready media can be shared", mediaId);
        }
        if (shares.countActiveForMedia(mediaId, now) >= properties.maxActiveSharesPerMedia()) {
            throw new ApplicationException(ApiErrorCode.QUOTA_EXCEEDED,
                    "too many active shares for this media", mediaId);
        }

        String rawToken = tokens.newToken();
        String shareId = UUID.randomUUID().toString();
        shares.insert(new ShareLinkRecord(shareId, mediaId, actorId, tokens.hash(rawToken),
                expiresAt, null, now));
        return new CreateShareResponse(shareId, rawToken, expiresAt.toString());
    }

    /** Shares of a media, manageable by their creators and by an owner. Never returns raw tokens. */
    @Transactional(readOnly = true)
    public List<ShareListItemView> list(String actorId, String mediaId) {
        MediaRecord record = requireVisible(actorId, mediaId);
        Role role = workspaces.roleOf(record.workspaceId(), actorId).orElse(null);
        return shares.listByMedia(mediaId, 100).stream()
                .filter(link -> rolePolicy.mayRevokeShare(role, actorId, link.creatorId()))
                .map(link -> new ShareListItemView(link.id(), link.expiresAt().toString(),
                        link.revokedAt() == null ? null : link.revokedAt().toString()))
                .toList();
    }

    /** Revokes a share. Revoking an already revoked share is not an error. */
    @Transactional
    public void revoke(String actorId, String shareId) {
        ShareLinkRecord link = shares.findById(shareId)
                .orElseThrow(() -> ApplicationException.notFound("share not found", shareId));
        MediaRecord record = media.findVisible(link.mediaId())
                .orElseThrow(() -> ApplicationException.notFound("share not found", shareId));
        Role role = workspaces.roleOf(record.workspaceId(), actorId).orElse(null);
        if (!rolePolicy.mayRevokeShare(role, actorId, link.creatorId())) {
            throw ApplicationException.notFound("share not found", shareId);
        }
        shares.revoke(shareId, clock.instant());
    }

    /**
     * Exchanges a raw token for a scoped session.
     *
     * <p>A token that is unknown, revoked, expired or whose media was deleted is reported as
     * absent, with no distinction between those cases.
     */
    @Transactional
    public Redemption redeem(String rawToken) {
        Instant now = clock.instant();
        ShareLinkRecord link = shares.findByTokenHash(tokens.hash(rawToken))
                .filter(candidate -> candidate.isActive(now))
                .orElseThrow(() -> ApplicationException.notFound("share not found", null));
        MediaRecord record = media.findVisible(link.mediaId())
                .orElseThrow(() -> ApplicationException.notFound("share not found", link.id()));
        if (record.status() != MediaState.READY) {
            throw ApplicationException.notFound("share not found", link.id());
        }
        String sessionId = UUID.randomUUID().toString();
        Instant sessionExpiry = link.expiresAt().isBefore(now.plus(Duration.ofDays(1)))
                ? link.expiresAt()
                : now.plus(Duration.ofDays(1));
        shareSessions.insert(new ShareSession(sessionId, link.id(), link.mediaId(), sessionExpiry));
        return new Redemption(sessionId,
                new ShareAccessView(record.title(), record.durationMs(), link.expiresAt().toString()));
    }

    /** Result of redeeming a token: the cookie value and the safe description to show. */
    public record Redemption(String sessionId, ShareAccessView view) {
    }

    /**
     * Resolves a share session to the media it may play, re-checking the share every time.
     *
     * <p>This is what makes revocation effective immediately: the session row alone is never
     * sufficient, the share must still be active at the moment of the request.
     */
    @Transactional(readOnly = true)
    public MediaRecord resolveSession(String sessionId) {
        Instant now = clock.instant();
        ShareSession session = shareSessions.findById(sessionId)
                .filter(candidate -> candidate.isActive(now))
                .orElseThrow(() -> ApplicationException.notFound("share session not found", null));
        ShareLinkRecord link = shares.findById(session.shareId())
                .filter(candidate -> candidate.isActive(now))
                .orElseThrow(() -> ApplicationException.notFound("share session not found", null));
        MediaRecord record = media.findVisible(link.mediaId())
                .orElseThrow(() -> ApplicationException.notFound("share session not found", null));
        if (record.status() != MediaState.READY) {
            throw ApplicationException.notFound("share session not found", null);
        }
        return record;
    }

    private MediaRecord requireVisible(String actorId, String mediaId) {
        MediaRecord record = media.findVisible(mediaId)
                .orElseThrow(() -> ApplicationException.notFound("media not found", mediaId));
        if (!rolePolicy.isVisible(workspaces.roleOf(record.workspaceId(), actorId).orElse(null))) {
            throw ApplicationException.notFound("media not found", mediaId);
        }
        return record;
    }
}
