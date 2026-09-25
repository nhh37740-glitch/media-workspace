package com.mediaworkspace.application.model;

/**
 * A stored chunk of an upload session.
 *
 * @param index      chunk index, from 0
 * @param hash       SHA-256 the server computed and verified for this chunk
 * @param sizeBytes  stored byte length
 * @param storageKey storage-relative path of the immutable chunk file
 */
public record UploadChunkRecord(int index, String hash, long sizeBytes, String storageKey) {
}
