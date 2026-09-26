package com.mediaworkspace.storage;

import com.mediaworkspace.application.port.storage.MediaStorage;
import com.mediaworkspace.application.port.storage.StoredObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Storage on a local volume, confined to one configured root directory.
 *
 * <p>Confinement is enforced on every operation, not only on the way in: a key is rejected outright
 * if it is absolute, contains a {@code ..} segment, contains a backslash, or if the path it resolves
 * to has a parent outside the root once symbolic links are resolved. A client-supplied file name is
 * never used to build a path here; callers pass keys they constructed from identifiers.
 *
 * <p>Publication is write-then-rename. The bytes are written to a temporary file in the destination
 * directory, the digest is computed while writing, and the file is moved into place with an atomic
 * rename. A reader therefore never sees a partial object, and an interrupted write leaves a
 * temporary file rather than a corrupt object under a real key.
 */
public class LocalMediaStorage implements MediaStorage {

    /** Directory under the root that holds files a child process is still producing. */
    public static final String TEMP_PREFIX = "tmp/";

    private final Path root;
    private final boolean fsyncOnPublish;

    /**
     * Prepares the storage root.
     *
     * <p>The directory is created when it is absent, because the configured path is this project's
     * own volume and an operator who provisioned the deployment should not also have to remember an
     * empty directory. Writability is then proven once, here, by writing and removing a probe file.
     *
     * <p>The alternative - discovering the problem on the first upload - was measured: it surfaced
     * as a 503 on a chunk request, which reads as a transient fault and gives no hint that the
     * volume is simply not there. Failing at startup names the path and the reason.
     *
     * @throws IllegalStateException when the root cannot be created or is not writable
     */
    public LocalMediaStorage(Path root, boolean fsyncOnPublish) {
        this.root = root.toAbsolutePath().normalize();
        this.fsyncOnPublish = fsyncOnPublish;
        verifyRootIsUsable();
    }

    private void verifyRootIsUsable() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "the storage root " + root + " does not exist and could not be created: "
                            + e.getMessage(), e);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("the storage root " + root + " is not a directory");
        }
        Path probe = root.resolve(".media-workspace-write-probe");
        try {
            Files.writeString(probe, "probe");
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "the storage root " + root + " is not writable by this process: " + e.getMessage(), e);
        }
    }

    public Path root() {
        return root;
    }

    @Override
    public InputStream open(String storageKey) throws StorageException {
        Path path = resolve(storageKey);
        try {
            return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new StorageException("cannot open " + storageKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public StoredObject putImmutable(String storageKey, InputStream data) throws StorageException {
        Path target = resolve(storageKey);
        prepareParent(target);
        Path staging = target.resolveSibling(target.getFileName() + ".part-" + System.nanoTime());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written;
            try (OutputStream fileOut = Files.newOutputStream(staging);
                 DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest)) {
                written = data.transferTo(digestOut);
                digestOut.flush();
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            return publish(staging, target, storageKey, written, sha256);
        } catch (NoSuchAlgorithmException e) {
            throw new StorageException("SHA-256 is required by the platform", e);
        } catch (IOException e) {
            deleteQuietly(staging);
            throw new StorageException("cannot publish " + storageKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public StoredObject promoteFile(String storageKey, Path sourceFile) throws StorageException {
        if (!sourceFile.toAbsolutePath().normalize().startsWith(root.resolve(TEMP_PREFIX))) {
            throw new InvalidKeyException("refusing to promote a file outside the temporary area");
        }
        boolean sourceInsideTemporaryArea;
        try {
            sourceInsideTemporaryArea = sourceFile.toRealPath()
                    .startsWith(root.toRealPath().resolve(TEMP_PREFIX));
        } catch (IOException e) {
            throw new StorageException("cannot verify the source file: " + e.getMessage(), e);
        }
        if (!sourceInsideTemporaryArea) {
            throw new InvalidKeyException("refusing to promote a file outside the temporary area");
        }
        Path target = resolve(storageKey);
        prepareParent(target);
        try {
            long size = Files.size(sourceFile);
            String sha256 = sha256Of(sourceFile);
            return publish(sourceFile, target, storageKey, size, sha256);
        } catch (IOException e) {
            throw new StorageException("cannot promote " + storageKey + ": " + e.getMessage(), e);
        }
    }

    /**
     * Moves a complete file into place.
     *
     * <p>An existing destination is never overwritten with different content. For a content-addressed
     * key an existing object with the same digest is the same object, so the new copy is discarded
     * and the stored one is reported; this is what makes repeating a merge or a chunk upload safe.
     */
    private StoredObject publish(Path staging, Path target, String storageKey, long size, String sha256)
            throws StorageException {
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                String existing = sha256Of(target);
                if (existing.equals(sha256)) {
                    deleteQuietly(staging);
                    return new StoredObject(storageKey, size, sha256);
                }
                deleteQuietly(staging);
                throw new StorageException(
                        "refusing to overwrite " + storageKey + " with different content");
            }
            if (fsyncOnPublish) {
                try (var channel = java.nio.channels.FileChannel.open(staging,
                        java.nio.file.StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(staging, target);
            }
            return new StoredObject(storageKey, size, sha256);
        } catch (IOException e) {
            if (e instanceof FileAlreadyExistsException) {
                // Another writer published the same content-addressed key first; that is success.
                deleteQuietly(staging);
                try {
                    return new StoredObject(storageKey, Files.size(target), sha256Of(target));
                } catch (IOException inner) {
                    throw new StorageException("cannot inspect " + storageKey, inner);
                }
            }
            deleteQuietly(staging);
            throw new StorageException("cannot publish " + storageKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Path createTemporaryFile(String tempKey) throws StorageException {
        if (!tempKey.startsWith(TEMP_PREFIX)) {
            throw new InvalidKeyException("temporary keys must start with " + TEMP_PREFIX);
        }
        Path path = resolve(tempKey);
        prepareParent(path);
        try {
            return Files.createFile(path);
        } catch (IOException e) {
            throw new StorageException("cannot create temporary file: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean deleteUnreferenced(String storageKey) throws StorageException {
        Path path = resolve(storageKey);
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            return Files.exists(resolve(storageKey), LinkOption.NOFOLLOW_LINKS);
        } catch (StorageException e) {
            return false;
        }
    }

    @Override
    public long sizeOf(String storageKey) throws StorageException {
        try {
            return Files.size(resolve(storageKey));
        } catch (IOException e) {
            throw new StorageException("cannot size " + storageKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Path resolve(String storageKey) throws StorageException {
        Path relative = validateKey(storageKey);
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new InvalidKeyException("key escapes the storage root: " + storageKey);
        }
        // A symlink inside the root must not lead outside it. Resolving the deepest existing
        // ancestor catches a link that only appears part way down the path.
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            try {
                Path real = existing.toRealPath();
                if (!real.startsWith(root.toRealPath())) {
                    throw new InvalidKeyException("key resolves outside the storage root: " + storageKey);
                }
            } catch (IOException e) {
                throw new StorageException("cannot verify " + storageKey, e);
            }
        }
        return candidate;
    }

    @Override
    public Path prepareForWrite(String storageKey) throws StorageException {
        Path path = resolve(storageKey);
        prepareParent(path);
        return path;
    }

    /**
     * Lists objects under a prefix.
     *
     * <p>Returns nothing rather than throwing when the prefix does not exist: the caller is a
     * collector sweeping for candidates, and an absent directory simply means there is nothing to
     * collect.
     */
    @Override
    public List<StoredObjectRef> list(String prefix) {
        String normalizedPrefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        Path base;
        try {
            base = resolve(normalizedPrefix);
        } catch (StorageException e) {
            return List.of();
        }
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(this::describe)
                    .filter(ref -> ref != null)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private StoredObjectRef describe(Path path) {
        try {
            return new StoredObjectRef(root.relativize(path).toString().replace('\\', '/'),
                    Files.size(path), Files.getLastModifiedTime(path).toInstant());
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public boolean isInsideRoot(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return false;
        }
        try {
            Path real = normalized.toRealPath();
            return real.startsWith(root.toRealPath());
        } catch (IOException e) {
            return normalized.startsWith(root);
        }
    }

    /**
     * Rejects a key that could name something other than a location inside the root.
     *
     * <p>Client-supplied strings are never keys, but a key is derived from identifiers at several
     * call sites, and one mistake there would be enough, so the check is repeated here.
     */
    static Path validateKey(String storageKey) throws InvalidKeyException {
        if (storageKey == null || storageKey.isBlank()) {
            throw new InvalidKeyException("storage key is empty");
        }
        if (storageKey.indexOf('\\') >= 0) {
            throw new InvalidKeyException("storage key must not contain a backslash: " + storageKey);
        }
        if (storageKey.startsWith("/") || storageKey.matches("^[A-Za-z]:.*")) {
            throw new InvalidKeyException("storage key must be relative: " + storageKey);
        }
        if (storageKey.indexOf('\0') >= 0) {
            throw new InvalidKeyException("storage key must not contain a NUL byte");
        }
        Path relative;
        try {
            relative = Path.of(storageKey);
        } catch (java.nio.file.InvalidPathException e) {
            throw new InvalidKeyException("storage key is not a valid path: " + storageKey);
        }
        if (relative.isAbsolute()) {
            throw new InvalidKeyException("storage key must be relative: " + storageKey);
        }
        for (Path segment : relative) {
            if (segment.toString().equals("..")) {
                throw new InvalidKeyException("storage key must not contain '..': " + storageKey);
            }
        }
        return relative;
    }

    private void prepareParent(Path target) throws StorageException {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new StorageException("cannot create the parent directory: " + e.getMessage(), e);
        }
    }

    private String sha256Of(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is required by the platform", e);
        }
    }

    private boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            return false;
        }
    }

    /** Last modified time, used by the collector to decide whether a candidate is old enough. */
    public Instant lastModified(String storageKey) throws StorageException {
        try {
            return Files.getLastModifiedTime(resolve(storageKey)).toInstant();
        } catch (IOException e) {
            throw new StorageException("cannot read the modification time of " + storageKey, e);
        }
    }
}
