package com.mediaworkspace.api.web;

import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.model.MediaRecord;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.service.MediaService;
import com.mediaworkspace.application.service.ShareService;
import com.mediaworkspace.contracts.dto.CreateShareRequest;
import com.mediaworkspace.contracts.dto.CreateShareResponse;
import com.mediaworkspace.contracts.dto.MediaDetailView;
import com.mediaworkspace.contracts.dto.MediaListItemView;
import com.mediaworkspace.contracts.dto.PageResponse;
import com.mediaworkspace.contracts.dto.PatchMediaRequest;
import com.mediaworkspace.contracts.dto.ShareListItemView;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.contracts.model.MediaState;
import com.mediaworkspace.contracts.model.Role;
import com.mediaworkspace.domain.access.RolePolicy;
import com.mediaworkspace.domain.access.SpaceAction;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;
import java.util.List;

/**
 * Media listing, detail, rename, deletion, playback and sharing.
 *
 * <p>Playback and posters are the paths where an authorization mistake is worst: they hand out bytes.
 * Both resolve the media through the same visibility check as the detail endpoint, and neither the
 * storage key nor any server path is ever part of a response body.
 */
@RestController
@RequestMapping("/api/v1")
public class MediaController {

    private final MediaService mediaService;
    private final ShareService shares;
    private final MediaRepository media;
    private final WorkspaceRepository workspaces;
    private final MediaStreamWriter streamWriter;
    private final CurrentUser currentUser;
    private final RolePolicy rolePolicy = new RolePolicy();

    public MediaController(MediaService mediaService, ShareService shares,
                           MediaRepository media, WorkspaceRepository workspaces,
                           MediaStreamWriter streamWriter, CurrentUser currentUser) {
        this.mediaService = mediaService;
        this.shares = shares;
        this.media = media;
        this.workspaces = workspaces;
        this.streamWriter = streamWriter;
        this.currentUser = currentUser;
    }

    @GetMapping("/spaces/{spaceId}/media")
    public PageResponse<MediaListItemView> list(
            @PathVariable String spaceId,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        return mediaService.list(currentUser.requireId(), spaceId, query, page, pageSize);
    }

    @GetMapping("/media/{mediaId}")
    public MediaDetailView detail(@PathVariable String mediaId) {
        return mediaService.detail(currentUser.requireId(), mediaId);
    }

    @PatchMapping("/media/{mediaId}")
    public MediaDetailView rename(@PathVariable String mediaId,
                                  @Valid @RequestBody PatchMediaRequest request) {
        return mediaService.rename(currentUser.requireId(), mediaId, request.title(), request.version());
    }

    @DeleteMapping("/media/{mediaId}")
    public ResponseEntity<Void> delete(@PathVariable String mediaId) {
        mediaService.delete(currentUser.requireId(), mediaId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Streams the encoded MP4.
     *
     * <p>Only a READY media is served, so a partially written artifact is never reachable. The
     * visibility check happens before the range is computed, so an unauthorised caller learns
     * nothing about the object's size.
     */
    @GetMapping("/media/{mediaId}/content")
    public ResponseEntity<StreamingResponseBody> content(
            @PathVariable String mediaId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        MediaRecord record = requireReadable(mediaId, SpaceAction.VIEW_MEDIA);
        if (record.status() != MediaState.READY || record.outputKey() == null) {
            throw ApplicationException.conflict("the media is not ready to play", mediaId);
        }
        return streamWriter.serve(record.outputKey(), range, MediaStreamWriter.videoContentType(),
                false);
    }

    /** Same headers as the GET, without the body. */
    @RequestMapping(value = "/media/{mediaId}/content", method = org.springframework.web.bind.annotation.RequestMethod.HEAD)
    public ResponseEntity<StreamingResponseBody> contentHead(
            @PathVariable String mediaId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        MediaRecord record = requireReadable(mediaId, SpaceAction.VIEW_MEDIA);
        if (record.status() != MediaState.READY || record.outputKey() == null) {
            throw ApplicationException.conflict("the media is not ready to play", mediaId);
        }
        return streamWriter.serve(record.outputKey(), range, MediaStreamWriter.videoContentType(), true);
    }

    @GetMapping("/media/{mediaId}/poster")
    public ResponseEntity<StreamingResponseBody> poster(@PathVariable String mediaId) {
        MediaRecord record = requireReadable(mediaId, SpaceAction.VIEW_MEDIA);
        if (record.status() != MediaState.READY || record.posterKey() == null) {
            throw ApplicationException.notFound("this media has no poster", mediaId);
        }
        return streamWriter.serve(record.posterKey(), null, MediaStreamWriter.posterContentType(), false);
    }

    /**
     * Creates a share link.
     *
     * <p>The raw token is in this response and nowhere else. It is not logged, not stored, and not
     * recoverable: a lost response is handled by revoking the orphan and creating another, which is
     * why this endpoint is deliberately excluded from automatic retries.
     */
    @PostMapping("/media/{mediaId}/shares")
    public ResponseEntity<CreateShareResponse> createShare(
            @PathVariable String mediaId,
            @Valid @RequestBody CreateShareRequest request) {
        Instant expiresAt = parseExpiry(request.expiresAt());
        CreateShareResponse created = shares.create(currentUser.requireId(), mediaId, expiresAt);
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/media/{mediaId}/shares")
    public List<ShareListItemView> listShares(@PathVariable String mediaId) {
        return shares.list(currentUser.requireId(), mediaId);
    }

    private Instant parseExpiry(String raw) {
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            throw ApplicationException.validation("expiresAt must be an ISO-8601 instant in UTC",
                    java.util.Map.of("value", raw));
        }
    }

    /** Resolves a media the caller may act on, applying the same rule as every other path. */
    private MediaRecord requireReadable(String mediaId, SpaceAction action) {
        String actorId = currentUser.requireId();
        MediaRecord record = media.findVisible(mediaId)
                .orElseThrow(() -> ApplicationException.notFound("media not found", mediaId));
        Role role = workspaces.roleOf(record.workspaceId(), actorId).orElse(null);
        if (!rolePolicy.isVisible(role)) {
            throw ApplicationException.notFound("media not found", mediaId);
        }
        if (!rolePolicy.allows(role, action)) {
            throw ApplicationException.forbidden("role does not permit this action", mediaId);
        }
        return record;
    }
}
