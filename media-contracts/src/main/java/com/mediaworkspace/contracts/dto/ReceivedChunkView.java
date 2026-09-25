package com.mediaworkspace.contracts.dto;

/**
 * One chunk already stored for an upload session, used to resume an interrupted transfer.
 *
 * @param index  chunk index
 * @param sha256 hash the server verified for this chunk
 */
public record ReceivedChunkView(int index, String sha256) {
}
