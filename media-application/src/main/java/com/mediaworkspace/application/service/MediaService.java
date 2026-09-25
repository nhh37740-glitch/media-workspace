package com.mediaworkspace.application.service;

import com.mediaworkspace.application.error.ApplicationException;
import com.mediaworkspace.application.model.MediaQuery;
import com.mediaworkspace.application.model.MediaRecord;
import com.mediaworkspace.application.model.ProcessingTaskRecord;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.port.repository.TaskRepository;
import com.mediaworkspace.application.port.repository.WorkspaceRepository;
import com.mediaworkspace.contracts.dto.MediaDetailView;
import com.mediaworkspace.contracts.dto.MediaListItemView;
import com.mediaworkspace.contracts.dto.PageResponse;
import com.mediaworkspace.contracts.error.ApiErrorCode;
import com.mediaworkspace.contracts.model.Role;
import com.mediaworkspace.domain.access.RolePolicy;
import com.mediaworkspace.domain.access.SpaceAction;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Media listing, detail, rename and deletion.
 *
 * <p>Deletion is a shadow: the row keeps its history but stops being visible, and every later read
 * path reports it as absent. An unfinished task is cancelled in the same transaction, because a
 * deletion that left a worker running would let it publish a file for a media nobody can reach.
 */
public class MediaService {

    /** Page size bounds and default from the contract. */
    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final MediaRepository media;
    private final TaskRepository tasks;
    private final WorkspaceRepository workspaces;
    private final TaskService taskService;
    private final RolePolicy rolePolicy = new RolePolicy();
    private final Clock clock;

    public MediaService(MediaRepository media, TaskRepository tasks, WorkspaceRepository workspaces,
                        TaskService taskService, Clock clock) {
        this.media = media;
        this.tasks = tasks;
        this.workspaces = workspaces;
        this.taskService = taskService;
        this.clock = clock;
    }

    /** Searches a space by title. A non-member sees the space as absent. */
    @Transactional(readOnly = true)
    public PageResponse<MediaListItemView> list(String actorId, String spaceId, String titleQuery,
                                                int page, int pageSize) {
        requireView(actorId, spaceId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(MIN_PAGE_SIZE, pageSize));
        MediaQuery query = new MediaQuery(spaceId, normalise(titleQuery), safePage, safeSize);
        List<MediaListItemView> items = new ArrayList<>();
        for (MediaRecord record : media.search(query)) {
            items.add(toListItem(record, taskIdOf(record.id())));
        }
        return PageResponse.of(items, safePage, safeSize, media.count(query));
    }

    /** Detail for a member, including the optimistic lock version a rename must send back. */
    @Transactional(readOnly = true)
    public MediaDetailView detail(String actorId, String mediaId) {
        MediaRecord record = requireVisibleMedia(actorId, mediaId, SpaceAction.VIEW_MEDIA);
        ProcessingTaskRecord task = tasks.findByMediaId(mediaId).orElse(null);
        return new MediaDetailView(
                record.id(), record.title(), record.status().name(),
                task == null ? null : task.id(),
                record.originalFilename(), record.sourceSize(), record.durationMs(),
                record.width(), record.height(),
                task == null ? null : task.errorCode(),
                record.version(), record.createdAt().toString());
    }

    /** Renames a media under optimistic locking. */
    @Transactional
    public MediaDetailView rename(String actorId, String mediaId, String title, long expectedVersion) {
        requireVisibleMedia(actorId, mediaId, SpaceAction.EDIT_MEDIA);
        if (!media.rename(mediaId, title, expectedVersion)) {
            throw new ApplicationException(ApiErrorCode.VERSION_CONFLICT,
                    "the media was modified by another request", mediaId);
        }
        return detail(actorId, mediaId);
    }

    /**
     * Deletes a media and cancels any unfinished task.
     *
     * <p>Both happen in one transaction so a worker cannot observe a deleted media while still
     * holding a live task, and the quota bytes are returned once.
     */
    @Transactional
    public void delete(String actorId, String mediaId) {
        MediaRecord record = requireVisibleMedia(actorId, mediaId, SpaceAction.DELETE_MEDIA);
        if (!media.markDeleted(mediaId)) {
            // Already deleted by a concurrent request; the end state is the one the caller wanted.
            return;
        }
        workspaces.releaseUsedBytes(record.workspaceId(), record.sourceSize());
        tasks.findByMediaId(record.id())
                .filter(task -> task.state().isActive())
                .ifPresent(task -> taskService.cancelInternal(task.id(), "MEDIA_DELETED"));
    }

    /** Resolves a media the actor may see, reporting a non-member and a missing row identically. */
    MediaRecord requireVisibleMedia(String actorId, String mediaId, SpaceAction action) {
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

    private void requireView(String actorId, String spaceId) {
        Role role = workspaces.roleOf(spaceId, actorId).orElse(null);
        if (!rolePolicy.allows(role, SpaceAction.VIEW_MEDIA)) {
            throw ApplicationException.notFound("space not found", spaceId);
        }
    }

    private MediaListItemView toListItem(MediaRecord record, String taskId) {
        return new MediaListItemView(record.id(), record.title(), record.status().name(), taskId,
                record.durationMs(), record.width(), record.height(), record.createdAt().toString());
    }

    private String taskIdOf(String mediaId) {
        return tasks.findByMediaId(mediaId).map(ProcessingTaskRecord::id).orElse(null);
    }

    private String normalise(String titleQuery) {
        if (titleQuery == null) {
            return null;
        }
        String trimmed = titleQuery.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
