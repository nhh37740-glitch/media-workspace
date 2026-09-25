package com.mediaworkspace.domain.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** UP-02, UP-04: chunk count and per-chunk lengths follow from the declared size. */
class ChunkGeometryTest {

    private static final int CHUNK = 8 * 1024 * 1024;

    @Test
    @DisplayName("a size that is an exact multiple yields no extra chunk")
    void exactMultiple() {
        ChunkGeometry geometry = new ChunkGeometry(3L * CHUNK, CHUNK);
        assertThat(geometry.chunkCount()).isEqualTo(3);
        assertThat(geometry.expectedLengthOf(2)).isEqualTo(CHUNK);
    }

    @Test
    @DisplayName("a size one byte over a multiple yields one short final chunk")
    void oneByteOver() {
        ChunkGeometry geometry = new ChunkGeometry(3L * CHUNK + 1, CHUNK);
        assertThat(geometry.chunkCount()).isEqualTo(4);
        assertThat(geometry.expectedLengthOf(3)).isEqualTo(1);
    }

    @Test
    @DisplayName("a file smaller than one chunk is a single chunk")
    void singleChunk() {
        ChunkGeometry geometry = new ChunkGeometry(1024, CHUNK);
        assertThat(geometry.chunkCount()).isEqualTo(1);
        assertThat(geometry.expectedLengthOf(0)).isEqualTo(1024);
        assertThat(geometry.offsetOf(0)).isZero();
    }

    @Test
    @DisplayName("offsets are contiguous and cover the whole file")
    void offsetsAreContiguous() {
        ChunkGeometry geometry = new ChunkGeometry(20_000_000L, CHUNK);
        long total = 0;
        for (int i = 0; i < geometry.chunkCount(); i++) {
            assertThat(geometry.offsetOf(i)).isEqualTo(total);
            total += geometry.expectedLengthOf(i);
        }
        assertThat(total).isEqualTo(20_000_000L);
    }

    @Test
    @DisplayName("an out-of-range index is rejected rather than clamped")
    void rejectsBadIndex() {
        ChunkGeometry geometry = new ChunkGeometry(10, 4);
        assertThat(geometry.chunkCount()).isEqualTo(3);
        assertThat(geometry.isValidIndex(2)).isTrue();
        assertThat(geometry.isValidIndex(3)).isFalse();
        assertThat(geometry.isValidIndex(-1)).isFalse();
        assertThatThrownBy(() -> geometry.expectedLengthOf(3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> geometry.offsetOf(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("a non-positive size or chunk size is a programming error")
    void rejectsNonPositive() {
        assertThatThrownBy(() -> new ChunkGeometry(0, CHUNK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkGeometry(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
