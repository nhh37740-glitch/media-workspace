package com.mediaworkspace.application.port.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Streaming access to the shared media volume.
 *
 * <p>Ownership rules, which callers must follow:
 * <ul>
 *   <li>the caller that obtains a stream from {@link #open} closes it;</li>
 *   <li>{@link #putImmutable} does <em>not</em> close the stream it is given; it closes the file
 *       handle it opens internally.</li>
 * </ul>
 *
 * <p>All keys are storage-root-relative. An absolute path, a {@code ..} segment or a symlink that
 * escapes the root is rejected: no caller may name a path outside the configured root, and no
 * client-supplied string is ever used as a path component.
 *
 * <p>Filesystem writes are not part of any database transaction. The rule that replaces atomicity
 * is ordering: a complete, verified object is written first, and only then may a row reference it.
 * A crash can therefore leave an unreferenced file, but never a row pointing at a partial file.
 */
public interface MediaStorage {

    /**
     * Opens an existing object for reading.
     *
     * @param storageKey root-relative key
     * @return an open stream the caller must close
     * @throws StorageException when the key is invalid or the object is absent
     */
    InputStream open(String storageKey) throws StorageException;

    /**
     * Publishes bytes under a new immutable key.
     *
     * <p>The object becomes visible only after every byte was written and the digest computed. An
     * existing key is never overwritten with different content; republishing byte-identical
     * content under a content-addressed key is accepted as already published.
     *
     * @param storageKey root-relative destination key
     * @param data       bytes to write; not closed by this method
     * @throws StorageException when the key is invalid, the volume is full, or writing fails
     */
    StoredObject putImmutable(String storageKey, InputStream data) throws StorageException;

    /**
     * Promotes an existing file that a child process wrote, such as an FFmpeg output.
     *
     * <p>The source is moved rather than copied. It must be inside the storage root's temporary
     * area, so a caller cannot promote an arbitrary host file into the media volume.
     *
     * @param storageKey root-relative destination key
     * @param sourceFile absolute path of the produced file
     */
    StoredObject promoteFile(String storageKey, Path sourceFile) throws StorageException;

    /**
     * Creates a fresh, empty file inside the temporary area.
     *
     * @param tempKey root-relative key under the temporary prefix
     * @return absolute path of the created file
     */
    Path createTemporaryFile(String tempKey) throws StorageException;

    /**
     * Removes an object, but only when the caller has proven no row references it.
     *
     * <p>Only the delayed garbage collector calls this. There is no HTTP route that reaches it and
     * it accepts no client-supplied path.
     *
     * @return {@code true} when the object was removed or was already absent
     */
    boolean deleteUnreferenced(String storageKey) throws StorageException;

    boolean exists(String storageKey);

    long sizeOf(String storageKey) throws StorageException;

    /** Absolute path of a storage key, for handing to a child process such as FFmpeg. */
    Path resolve(String storageKey) throws StorageException;

    /** Objects under a prefix, used by the garbage collector to find candidates. */
    List<StoredObjectRef> list(String prefix);

    /** Creates the directories a storage key needs, for a child process that writes a new file. */
    Path prepareForWrite(String storageKey) throws StorageException;

    /** Whether a path lies inside the configured storage root. */
    boolean isInsideRoot(Path candidate);

    /** Minimal description of a stored object used by listing and garbage collection. */
    record StoredObjectRef(String storageKey, long sizeBytes, Instant modifiedAt) {
    }

    /** Storage failure. */
    class StorageException extends IOException {
        private static final long serialVersionUID = 1L;

        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The requested key is not a legal storage-root-relative key. */
    class InvalidKeyException extends StorageException {
        private static final long serialVersionUID = 1L;

        public InvalidKeyException(String message) {
            super(message);
        }
    }
}
