package com.mediaworkspace.domain.upload;

/**
 * Slicing arithmetic for a multipart upload.
 *
 * <p>The server, not the client, decides chunk geometry: a client is told the chunk size and the
 * chunk count and cannot choose either. A declared size of {@code n} bytes with chunk size
 * {@code c} yields {@code ceil(n / c)} chunks where every chunk except the last has exactly
 * {@code c} bytes.
 */
public record ChunkGeometry(long sizeBytes, int chunkSize) {

    public ChunkGeometry {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
    }

    /** Total number of chunks, always at least 1. */
    public int chunkCount() {
        return (int) ((sizeBytes + chunkSize - 1) / chunkSize);
    }

    /** Expected byte length of the chunk at {@code index}; throws when the index is out of range. */
    public long expectedLengthOf(int index) {
        if (index < 0 || index >= chunkCount()) {
            throw new IndexOutOfBoundsException("chunk index out of range: " + index);
        }
        long offset = (long) index * chunkSize;
        return Math.min(chunkSize, sizeBytes - offset);
    }

    /** Byte offset of the chunk at {@code index} in the merged file. */
    public long offsetOf(int index) {
        if (index < 0 || index >= chunkCount()) {
            throw new IndexOutOfBoundsException("chunk index out of range: " + index);
        }
        return (long) index * chunkSize;
    }

    public boolean isValidIndex(int index) {
        return index >= 0 && index < chunkCount();
    }
}
