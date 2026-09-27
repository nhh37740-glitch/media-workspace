import { createHash, webcrypto } from 'node:crypto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request, refreshCsrf } from './client'
import { sha256OfFile, uploadFile } from './uploader'

vi.mock('./client', () => ({
  media: {},
  newIdempotencyKey: () => 'upload-test-key',
  request: vi.fn(),
  refreshCsrf: vi.fn()
}))

function fileOf(bytes) {
  return {
    name: 'clip.mp4',
    size: bytes.length,
    arrayBuffer: vi.fn(async () => bytes.slice().buffer),
    slice: vi.fn((start, end) => ({ arrayBuffer: async () => bytes.slice(start, end).buffer }))
  }
}

function expectedHash(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

function mockUploadSession() {
  request.mockImplementation(async (method, path) => {
    if (method === 'POST' && path === '/spaces/space-1/uploads') {
      return { uploadId: 'upload-1', chunkSize: 3, chunkCount: 2 }
    }
    if (method === 'POST' && path === '/uploads/upload-1/complete') {
      return { mediaId: 'media-1', taskId: 'task-1' }
    }
    return null
  })
}

describe('upload SHA-256 hashing', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  // NIST SHA-256 one-block and two-block examples:
  // https://csrc.nist.gov/csrc/media/projects/cryptographic-standards-and-guidelines/documents/examples/sha256.pdf
  it.each([
    ['abc', 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'],
    ['abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq',
      '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1']
  ])('matches the NIST SHA-256 vector for %s without Web Crypto', async (message, expected) => {
    vi.stubGlobal('crypto', { subtle: undefined })
    expect(await sha256OfFile(fileOf(new TextEncoder().encode(message)))).toBe(expected)
  })

  it('hashes a file in bounded slices when crypto.subtle is unavailable', async () => {
    vi.stubGlobal('crypto', { subtle: undefined })
    const bytes = new Uint8Array(2 * 1024 * 1024 + 77)
    for (let index = 0; index < bytes.length; index++) bytes[index] = index % 251
    const file = fileOf(bytes)
    const progress = vi.fn()

    expect(await sha256OfFile(file, progress)).toBe(expectedHash(bytes))
    expect(file.arrayBuffer).not.toHaveBeenCalled()
    expect(file.slice.mock.calls).toEqual([
      [0, 2 * 1024 * 1024],
      [2 * 1024 * 1024, bytes.length]
    ])
    expect(progress).toHaveBeenLastCalledWith(100)
  })

  it.each([0, 55, 56, 63, 64, 65, 119, 120, 127, 128])(
    'matches SHA-256 at a %i-byte padding boundary without Web Crypto', async (size) => {
      vi.stubGlobal('crypto', { subtle: undefined })
      const bytes = Uint8Array.from({ length: size }, (_, index) => index % 251)
      expect(await sha256OfFile(fileOf(bytes))).toBe(expectedHash(bytes))
    }
  )

  it('sends unchanged whole-file and chunk hashes without crypto.subtle', async () => {
    vi.stubGlobal('crypto', undefined)
    mockUploadSession()
    const bytes = new TextEncoder().encode('abcdef')

    await expect(uploadFile('space-1', fileOf(bytes))).resolves.toEqual({
      uploadId: 'upload-1', mediaId: 'media-1', taskId: 'task-1'
    })

    expect(refreshCsrf).toHaveBeenCalledOnce()
    expect(request).toHaveBeenCalledWith('POST', '/spaces/space-1/uploads', expect.objectContaining({
      body: expect.objectContaining({ sha256: expectedHash(bytes) })
    }))
    for (let index = 0; index < 2; index++) {
      expect(request).toHaveBeenCalledWith('PUT', `/uploads/upload-1/chunks/${index}`, expect.objectContaining({
        headers: expect.objectContaining({ 'X-Chunk-SHA256': expectedHash(bytes.slice(index * 3, index * 3 + 3)) })
      }))
    }
  })

  it('uses Web Crypto for both file and chunks when available', async () => {
    const digest = vi.fn((algorithm, buffer) => webcrypto.subtle.digest(algorithm, buffer))
    vi.stubGlobal('crypto', { subtle: { digest } })
    mockUploadSession()
    const bytes = new TextEncoder().encode('abcdef')
    const file = fileOf(bytes)

    await uploadFile('space-1', file)

    expect(file.arrayBuffer).toHaveBeenCalledOnce()
    expect(digest).toHaveBeenCalledTimes(3)
    expect(digest.mock.calls.every(([algorithm]) => algorithm === 'SHA-256')).toBe(true)
    expect(request).toHaveBeenCalledWith('POST', '/spaces/space-1/uploads', expect.objectContaining({
      body: expect.objectContaining({ sha256: expectedHash(bytes) })
    }))
    expect(request).toHaveBeenCalledWith('PUT', '/uploads/upload-1/chunks/0', expect.objectContaining({
      headers: expect.objectContaining({ 'X-Chunk-SHA256': expectedHash(bytes.slice(0, 3)) })
    }))
    expect(request).toHaveBeenCalledWith('PUT', '/uploads/upload-1/chunks/1', expect.objectContaining({
      headers: expect.objectContaining({ 'X-Chunk-SHA256': expectedHash(bytes.slice(3, 6)) })
    }))
  })
})
