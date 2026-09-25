package com.mediaworkspace.application.service;

import com.mediaworkspace.application.model.MediaRecord;
import com.mediaworkspace.application.model.UploadChunkRecord;
import com.mediaworkspace.application.model.UploadSession;
import com.mediaworkspace.application.port.repository.MediaRepository;
import com.mediaworkspace.application.port.repository.UploadRepository;
import com.mediaworkspace.application.port.storage.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Collects storage the database no longer references.
 *
 * <p>A database transaction and a filesystem write cannot be made atomic together, so this system
 * chooses an order that is safe in one direction only: the complete file is written first and the
 * row that references it is committed afterwards. The cost of that choice is orphan files, never a
 * row pointing at a half-written object. This class pays the cost down.
 *
 * <p>Three rules keep it from deleting something still needed:
 * <ol>
 *   <li>a grace period longer than any valid run or recovery window;</li>
 *   <li>a database reference check before every deletion, rather than an inference from the
 *       directory layout;</li>
 *   <li>sessions that ended recently are left alone, because a finalizer that lost its lease may
 *       still be writing into paths a sweep would otherwise treat as abandoned.</li>
 * </ol>
 *
 * <p>It only ever touches keys this project generated, under the temporary, chunk, merge and derived
 * prefixes, and only inside the configured storage root. There is no route that reaches it.
 *
 * <p>Every storage failure is caught and logged: a collector that threw would abort the rest of the
 * sweep and, worse, make a maintenance pass look like a business failure.
 */
public class StorageMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(StorageMaintenanceService.class);

    private final MediaStorage storage;
    private final UploadRepository uploads;
    private final MediaRepository media;
    private final Duration gracePeriod;

    public StorageMaintenanceService(MediaStorage storage, UploadRepository uploads, MediaRepository media,
                                     Duration gracePeriod) {
        this.storage = storage;
        this.uploads = uploads;
        this.media = media;
        this.gracePeriod = gracePeriod;
    }

    /** What one collection pass removed, for the log line and for the tests. */
    public record SweepResult(int temporaryFiles, int chunkFiles, int mergeFiles, int derivedFiles) {
        public int total() {
            return temporaryFiles + chunkFiles + mergeFiles + derivedFiles;
        }
    }

    /**
     * Runs one collection pass.
     *
     * @param limit maximum objects to consider per category in this pass
     */
    public SweepResult sweep(int limit) {
        Instant cutoff = Instant.now().minus(gracePeriod);
        int temporary = sweepTemporaries(cutoff, limit);
        SweepResult finished = sweepFinishedUploads(limit);
        int derived = sweepDeletedMedia(limit);
        SweepResult result = new SweepResult(temporary, finished.chunkFiles(), finished.mergeFiles(), derived);
        if (result.total() > 0) {
            log.info("storage sweep removed {} object(s): {} temporary, {} chunk, {} merge, {} derived",
                    result.total(), result.temporaryFiles(), result.chunkFiles(),
                    result.mergeFiles(), result.derivedFiles());
        }
        return result;
    }

    /**
     * Removes files left in the temporary area.
     *
     * <p>Nothing in the database ever references a temporary path: a file acquires a reference only
     * after it is promoted to an immutable key. Age is therefore sufficient evidence here.
     */
    private int sweepTemporaries(Instant cutoff, int limit) {
        int removed = 0;
        for (MediaStorage.StoredObjectRef candidate : storage.list("tmp")) {
            if (removed >= limit) {
                break;
            }
            if (candidate.modifiedAt().isAfter(cutoff)) {
                continue;
            }
            if (deleteIfUnreferenced(candidate.storageKey())) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes chunks of sessions that ended, and the merge scratch file once the original has been
     * published.
     *
     * <p>A completed session's chunks are unreferenced because the media row points at the merged
     * original, not at them. The merged original itself is kept while the media lives: it is the
     * source a retry would re-read.
     */
    private SweepResult sweepFinishedUploads(int limit) {
        int chunkFiles = 0;
        int mergeFiles = 0;
        List<UploadSession> terminal = uploads.findTerminalBefore(gracePeriod, limit);
        for (UploadSession session : terminal) {
            for (UploadChunkRecord chunk : uploads.listChunks(session.id())) {
                if (deleteIfUnreferenced(chunk.storageKey())) {
                    chunkFiles++;
                }
            }
            if (session.mediaId() != null) {
                String mergeKey = mergeKeyOf(session);
                if (!media.isStorageKeyReferenced(mergeKey) && deleteIfUnreferenced(mergeKey)) {
                    mergeFiles++;
                }
            }
        }
        return new SweepResult(0, chunkFiles, mergeFiles, 0);
    }

    /**
     * Removes the files of media deleted long enough ago.
     *
     * <p>The row is shadowed rather than removed, so a reference check still finds it. That is why
     * the deletion is driven by the deletion timestamp and not by the reference count: after the
     * grace period the row is the only thing still naming these keys, and the media is unreachable
     * by any route.
     */
    private int sweepDeletedMedia(int limit) {
        int removed = 0;
        for (MediaRecord record : media.findDeletedBefore(gracePeriod, limit)) {
            for (String key : new String[] {record.outputKey(), record.posterKey(), record.sourceKey()}) {
                if (key == null || !storage.exists(key)) {
                    continue;
                }
                if (deleteIfUnreferenced(key)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Deletes an object when no row references it.
     *
     * <p>Also skips an object that does not exist, so a repeated pass does not report phantom
     * deletions. The reference check happens immediately before the delete, not at the start of the
     * pass, so a row created while the pass runs still protects its file.
     */
    private boolean deleteIfUnreferenced(String storageKey) {
        try {
            if (!storage.exists(storageKey) || media.isStorageKeyReferenced(storageKey)) {
                return false;
            }
            return storage.deleteUnreferenced(storageKey);
        } catch (MediaStorage.StorageException e) {
            log.warn("skipping {} during collection: {}", storageKey, e.getMessage());
            return false;
        }
    }

    /** The content-addressed merge key a session's original would live at. */
    static String mergeKeyOf(UploadSession session) {
        return "merge/" + session.id() + "/" + session.expectedHash() + "/original.bin";
    }
}
