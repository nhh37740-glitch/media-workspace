package com.mediaworkspace.storage;

import com.mediaworkspace.application.port.storage.MediaStorage;
import com.mediaworkspace.application.port.storage.StoredObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** UP-11 and PROC-07: nothing a caller passes may name a path outside the configured root. */
class LocalMediaStorageTest {

    @TempDir
    Path root;

    private LocalMediaStorage storage() {
        return new LocalMediaStorage(root, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/etc/passwd",
            "../outside.bin",
            "a/../../outside.bin",
            "..",
            "chunk/../../../etc/shadow",
            "C:/windows/system32/config",
            "a\\b.bin",
            "chunk/..\\..\\escape",
            "",
            "   ",
    })
    @DisplayName("a key that could leave the root is rejected outright")
    void rejectsEscapingKeys(String key) {
        LocalMediaStorage storage = storage();
        assertThatThrownBy(() -> storage.resolve(key))
                .as("key %s", key)
                .isInstanceOf(MediaStorage.StorageException.class);
    }

    @Test
    @DisplayName("a legal key resolves to a path inside the root")
    void acceptsLegalKeys() throws Exception {
        LocalMediaStorage storage = storage();
        Path resolved = storage.resolve("chunk/abc/0-deadbeef");
        // Compared as normalized paths rather than with PathAssert.startsWith, which resolves the
        // path on disk and would require the object to exist before it has been written.
        assertThat(resolved.normalize().toString())
                .startsWith(root.toAbsolutePath().normalize().toString());
        assertThat(storage.isInsideRoot(resolved)).isTrue();
    }

    @Test
    @DisplayName("a symbolic link pointing outside the root is refused even though the link is inside")
    void rejectsSymlinkEscape() throws Exception {
        LocalMediaStorage storage = storage();
        Path outside = Files.createTempDirectory("mw-outside");
        Path link = root.resolve("escape-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // The platform forbids creating links; nothing to assert here.
        }
        assertThatThrownBy(() -> storage.resolve("escape-link/stolen.bin"))
                .isInstanceOf(MediaStorage.StorageException.class);
    }

    @Test
    @DisplayName("publishing writes the object and reports its size and digest")
    void publishesImmutably() throws Exception {
        LocalMediaStorage storage = storage();
        byte[] content = "hello media workspace".getBytes(StandardCharsets.UTF_8);
        StoredObject stored = storage.putImmutable("chunk/u1/0-abc",
                new ByteArrayInputStream(content));

        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(stored.sha256()).hasSize(64);
        assertThat(Files.readAllBytes(root.resolve("chunk/u1/0-abc"))).isEqualTo(content);
        assertThat(storage.exists("chunk/u1/0-abc")).isTrue();
    }

    @Test
    @DisplayName("republishing identical content under a content-addressed key is accepted")
    void republishIdenticalIsIdempotent() throws Exception {
        LocalMediaStorage storage = storage();
        byte[] content = "same bytes".getBytes(StandardCharsets.UTF_8);
        StoredObject first = storage.putImmutable("chunk/u1/0-abc", new ByteArrayInputStream(content));
        StoredObject second = storage.putImmutable("chunk/u1/0-abc", new ByteArrayInputStream(content));

        assertThat(second.sha256()).isEqualTo(first.sha256());
        assertThat(Files.readAllBytes(root.resolve("chunk/u1/0-abc"))).isEqualTo(content);
    }

    @Test
    @DisplayName("publishing different content over an existing key is refused, leaving the original")
    void refusesToOverwriteWithDifferentContent() throws Exception {
        LocalMediaStorage storage = storage();
        storage.putImmutable("chunk/u1/0-abc",
                new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> storage.putImmutable("chunk/u1/0-abc",
                new ByteArrayInputStream("replacement".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(MediaStorage.StorageException.class);

        assertThat(Files.readAllBytes(root.resolve("chunk/u1/0-abc")))
                .isEqualTo("original".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a failed write leaves no object under the final key")
    void failedWriteLeavesNothing() throws Exception {
        LocalMediaStorage storage = storage();
        java.io.InputStream failing = new java.io.InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                if (served++ > 10) {
                    throw new IOException("the source broke mid-transfer");
                }
                return 'x';
            }
        };
        assertThatThrownBy(() -> storage.putImmutable("chunk/u1/0-broken", failing))
                .isInstanceOf(MediaStorage.StorageException.class);
        assertThat(storage.exists("chunk/u1/0-broken")).isFalse();
        try (var entries = Files.walk(root)) {
            assertThat(entries.filter(Files::isRegularFile).count())
                    .as("no partial file is left behind")
                    .isZero();
        }
    }

    @Test
    @DisplayName("temporary files are only allowed under the temporary prefix")
    void temporaryFilesAreConfined() throws Exception {
        LocalMediaStorage storage = storage();
        Path temp = storage.createTemporaryFile("tmp/merge/u1/staging");
        assertThat(temp).startsWith(root);
        assertThatThrownBy(() -> storage.createTemporaryFile("derived/not-temporary"))
                .isInstanceOf(MediaStorage.InvalidKeyException.class);
    }

    @Test
    @DisplayName("promoting a file from outside the root is refused")
    void refusesToPromoteOutsideFile() throws Exception {
        LocalMediaStorage storage = storage();
        Path outside = Files.createTempFile("mw-outsider", ".bin");
        Files.writeString(outside, "not part of this storage");
        assertThatThrownBy(() -> storage.promoteFile("derived/m/1/1/output.mp4", outside))
                .isInstanceOf(MediaStorage.InvalidKeyException.class);
    }

    @Test
    @DisplayName("promoting a file inside the root but outside tmp is refused")
    void refusesToPromoteNonTemporaryFile() throws Exception {
        LocalMediaStorage storage = storage();
        Path source = root.resolve("derived/source.mp4");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "existing object");

        assertThatThrownBy(() -> storage.promoteFile("derived/m/1/1/output.mp4", source))
                .isInstanceOf(MediaStorage.InvalidKeyException.class);
        assertThatThrownBy(() -> storage.promoteFile("derived/m/1/1/output.mp4",
                root.resolve("tmp/../derived/source.mp4")))
                .isInstanceOf(MediaStorage.InvalidKeyException.class);
        assertThat(Files.exists(source)).isTrue();
    }

    @Test
    @DisplayName("a link under tmp cannot promote a file outside the temporary area")
    void refusesToPromoteSymlinkToNonTemporaryFile() throws Exception {
        LocalMediaStorage storage = storage();
        Path source = root.resolve("derived/source.mp4");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "existing object");
        Path link = root.resolve("tmp/linked-source.mp4");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, source);
        } catch (UnsupportedOperationException | IOException e) {
            return; // The platform forbids creating links; nothing to assert here.
        }

        assertThatThrownBy(() -> storage.promoteFile("derived/m/1/1/output.mp4", link))
                .isInstanceOf(MediaStorage.InvalidKeyException.class);
        assertThat(Files.exists(source)).isTrue();
    }

    @Test
    @DisplayName("deleting an unreferenced object is successful when it was already absent")
    void deleteUnreferencedIsIdempotent() throws Exception {
        LocalMediaStorage storage = storage();
        String key = "chunk/u1/0-abc";
        storage.putImmutable(key, new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));

        assertThat(storage.deleteUnreferenced(key)).isTrue();
        assertThat(storage.deleteUnreferenced(key)).isTrue();
        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    @DisplayName("constructing against an unusable root fails immediately, naming the path")
    void refusesUnusableRoot(@TempDir Path parent) throws Exception {
        // A root whose parent is a regular file can never be created. The failure must happen at
        // construction: discovering it on the first upload reported it as a transient server error,
        // which gives an operator no hint that the volume is simply not there.
        Path blocker = parent.resolve("not-a-directory");
        Files.writeString(blocker, "this is a file, not a container");
        Path wanted = blocker.resolve("storage");

        assertThatThrownBy(() -> new LocalMediaStorage(wanted, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(wanted.toString());
    }

    @Test
    @DisplayName("a missing root directory is created rather than reported as a fault")
    void createsMissingRoot(@TempDir Path parent) {
        Path wanted = parent.resolve("fresh/storage");
        assertThat(Files.exists(wanted)).isFalse();

        LocalMediaStorage storage = new LocalMediaStorage(wanted, false);

        assertThat(Files.isDirectory(storage.root())).isTrue();
        // The write probe must not be left behind.
        assertThat(storage.exists(".media-workspace-write-probe")).isFalse();
    }

    @Test
    @DisplayName("promoting a file the storage itself produced moves it into place")
    void promotesProducedFile() throws Exception {
        LocalMediaStorage storage = storage();
        Path staged = storage.createTemporaryFile("tmp/derived/m1/1/1/output.mp4");
        Files.writeString(staged, "encoded bytes");

        StoredObject stored = storage.promoteFile("derived/m1/1/1/output.mp4", staged);

        assertThat(stored.sizeBytes()).isEqualTo(13);
        assertThat(Files.exists(staged)).isFalse();
        assertThat(Files.readString(root.resolve("derived/m1/1/1/output.mp4")))
                .isEqualTo("encoded bytes");
    }
}
