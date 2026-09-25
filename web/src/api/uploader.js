import { media, newIdempotencyKey, request, refreshCsrf } from './client'

/**
 * Drives a whole upload from one user action.
 *
 * The user picks a file and presses upload once. Everything after that is this module: hashing the
 * file, asking the server how to slice it, sending the chunks, retrying the ones that failed, and
 * asking for the merge. The chunk size and the chunk count come from the server response and are
 * never chosen here, so two clients uploading the same file produce the same slicing.
 *
 * Progress is reported through a callback. It is a display hint: the authoritative state of the
 * upload and of the task it creates is whatever the server reports when asked.
 */

const CHUNK_RETRIES = 3
const RETRY_BASE_DELAY_MS = 500

/** SHA-256 of a file as lowercase hex, computed with the platform's own digest. */
export async function sha256OfFile(file, onProgress) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  onProgress?.(100)
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

/** SHA-256 of one chunk, used for the per-chunk header the server verifies. */
async function sha256OfChunk(buffer) {
  const digest = await crypto.subtle.digest('SHA-256', buffer)
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

/**
 * Sends one chunk, retrying a transient failure.
 *
 * A retry re-reads the same slice of the file, so a chunk that was interrupted mid-transfer is
 * simply sent again. The server stores an index once; a repeat with the same bytes is reported as
 * already stored rather than as a second chunk.
 */
async function putChunk(uploadId, index, slice, sha256, signal) {
  let lastError
  for (let attempt = 0; attempt <= CHUNK_RETRIES; attempt++) {
    try {
      if (signal?.aborted) {
        throw new DOMException('upload cancelled', 'AbortError')
      }
      return await request('PUT', `/uploads/${uploadId}/chunks/${index}`, {
        raw: slice,
        headers: {
          'Content-Type': 'application/octet-stream',
          'X-Chunk-SHA256': sha256
        },
        signal
      })
    } catch (error) {
      if (error.name === 'AbortError') {
        throw error
      }
      lastError = error
      // A 4xx other than a quota or capacity answer means this chunk will never be accepted as it
      // is, so retrying would only waste the user's time.
      if (error.status >= 400 && error.status < 500 && error.status !== 429) {
        throw error
      }
      await delay(RETRY_BASE_DELAY_MS * Math.pow(2, attempt))
    }
  }
  throw lastError
}

/**
 * Uploads a file and asks for the merge.
 *
 * @param {string} spaceId workspace to upload into
 * @param {File} file the user chose
 * @param {{title?: string, onProgress?: (state: object) => void, signal?: AbortSignal}} options
 * @returns {Promise<{uploadId: string, mediaId: string|null, taskId: string|null}>}
 */
export async function uploadFile(spaceId, file, options = {}) {
  const { onProgress, signal } = options
  const report = (state) => onProgress?.({ file: file.name, ...state })

  report({ phase: 'hashing', percent: 0 })
  const wholeFileHash = await sha256OfFile(file, (percent) =>
    report({ phase: 'hashing', percent })
  )

  report({ phase: 'creating', percent: 0 })
  await refreshCsrf()
  const session = await request('POST', `/spaces/${spaceId}/uploads`, {
    body: {
      filename: file.name,
      sizeBytes: file.size,
      sha256: wholeFileHash,
      title: options.title || file.name
    },
    headers: { 'Idempotency-Key': newIdempotencyKey() },
    signal
  })

  const { uploadId, chunkSize, chunkCount } = session
  for (let index = 0; index < chunkCount; index++) {
    const start = index * chunkSize
    const slice = file.slice(start, Math.min(start + chunkSize, file.size))
    const buffer = await slice.arrayBuffer()
    const chunkHash = await sha256OfChunk(buffer)
    await putChunk(uploadId, index, slice, chunkHash, signal)
    report({
      phase: 'uploading',
      percent: Math.round(((index + 1) / chunkCount) * 100),
      uploadedChunks: index + 1,
      chunkCount
    })
  }

  report({ phase: 'finalizing', percent: 100 })
  const status = await request('POST', `/uploads/${uploadId}/complete`, { body: {}, signal })
  return { uploadId, mediaId: status.mediaId ?? null, taskId: status.taskId ?? null }
}

/** Aborts a session that is still open, releasing its quota reservation. */
export async function abortUpload(uploadId) {
  return request('DELETE', `/uploads/${uploadId}`)
}

/** Reads the session so an interrupted transfer can be resumed without re-sending stored chunks. */
export async function uploadStatus(uploadId) {
  return request('GET', `/uploads/${uploadId}`)
}

/** Convenience re-export so a view can refresh a media row without a second import. */
export { media }
