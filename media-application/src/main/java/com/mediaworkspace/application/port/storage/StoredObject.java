package com.mediaworkspace.application.port.storage;

/**
 * Result of publishing an object to the storage volume.
 *
 * @param storageKey root-relative key
 * @param sizeBytes  byte length actually written
 * @param sha256     lowercase hex SHA-256 computed while writing
 */
public record StoredObject(String storageKey, long sizeBytes, String sha256) {
}
