import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, auth, clearCsrf, media, newIdempotencyKey, refreshCsrf, request, tasks } from './client'

/**
 * The client's job is to attach the right credentials and to turn an error body into something a
 * caller can branch on. Both are asserted against a stubbed fetch, because the behaviour under test
 * is what the client sends and how it interprets the reply, not the network.
 */
function jsonResponse(body, { status = 200, ok = true } = {}) {
  return {
    ok,
    status,
    text: async () => (body === undefined ? '' : JSON.stringify(body)),
    json: async () => body
  }
}

describe('api client', () => {
  beforeEach(() => {
    clearCsrf()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('fetches a CSRF token first and sends it on an unsafe request', async () => {
    const calls = []
    vi.stubGlobal('fetch', async (url, options) => {
      calls.push({ url, options })
      if (url.endsWith('/auth/csrf')) {
        return jsonResponse({ token: 'token-1', headerName: 'X-CSRF-TOKEN' })
      }
      return jsonResponse({ spaceId: 's1' }, { status: 201 })
    })

    await request('POST', '/spaces', { body: { name: 'demo' } })

    expect(calls).toHaveLength(2)
    const post = calls[1]
    expect(post.options.method).toBe('POST')
    expect(post.options.headers['X-CSRF-TOKEN']).toBe('token-1')
    expect(post.options.credentials).toBe('same-origin')
    expect(JSON.parse(post.options.body)).toEqual({ name: 'demo' })
  })

  it('does not fetch a token for a read', async () => {
    const calls = []
    vi.stubGlobal('fetch', async (url, options) => {
      calls.push({ url, options })
      return jsonResponse({ items: [] })
    })

    await request('GET', '/spaces')

    expect(calls).toHaveLength(1)
    expect(calls[0].options.headers['X-CSRF-TOKEN']).toBeUndefined()
  })

  it('reuses a token across requests rather than fetching one each time', async () => {
    let csrfCalls = 0
    vi.stubGlobal('fetch', async (url) => {
      if (url.endsWith('/auth/csrf')) {
        csrfCalls += 1
        return jsonResponse({ token: 'shared', headerName: 'X-CSRF-TOKEN' })
      }
      return jsonResponse({}, { status: 204 })
    })

    await request('POST', '/a', { body: {} })
    await request('POST', '/b', { body: {} })

    expect(csrfCalls).toBe(1)
  })

  it('turns an error body into a typed error carrying the stable code', async () => {
    vi.stubGlobal('fetch', async (url) => {
      if (url.endsWith('/auth/csrf')) {
        return jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' })
      }
      return jsonResponse(
        { code: 'CHUNKS_MISSING', message: 'not every chunk has been received', requestId: 'r-1' },
        { status: 409, ok: false }
      )
    })

    await expect(request('POST', '/uploads/x/complete', { body: {} })).rejects.toMatchObject({
      name: 'ApiError',
      code: 'CHUNKS_MISSING',
      status: 409,
      requestId: 'r-1'
    })
  })

  it('marks a server-side failure as retryable and a client error as not', async () => {
    vi.stubGlobal('fetch', async (url) => {
      if (url.endsWith('/auth/csrf')) {
        return jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' })
      }
      return jsonResponse({ code: 'SERVICE_UNAVAILABLE', message: 'later' }, { status: 503, ok: false })
    })
    await expect(request('POST', '/x', { body: {} })).rejects.toSatisfy(
      (error) => error instanceof ApiError && error.retryable === true
    )

    clearCsrf()
    vi.stubGlobal('fetch', async (url) => {
      if (url.endsWith('/auth/csrf')) {
        return jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' })
      }
      return jsonResponse({ code: 'VALIDATION_FAILED', message: 'bad' }, { status: 422, ok: false })
    })
    await expect(request('POST', '/x', { body: {} })).rejects.toSatisfy(
      (error) => error instanceof ApiError && error.retryable === false
    )
  })

  it('returns null for a response with no body', async () => {
    vi.stubGlobal('fetch', async (url) => {
      if (url.endsWith('/auth/csrf')) {
        return jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' })
      }
      return jsonResponse(undefined, { status: 204 })
    })
    await expect(request('DELETE', '/media/m1')).resolves.toBeNull()
  })

  it('rotates the token after a login, because the session changed', async () => {
    const csrfTokens = ['before', 'after']
    let csrfIndex = 0
    vi.stubGlobal('fetch', async (url) => {
      if (url.endsWith('/auth/csrf')) {
        const token = csrfTokens[Math.min(csrfIndex, csrfTokens.length - 1)]
        csrfIndex += 1
        return jsonResponse({ token, headerName: 'X-CSRF-TOKEN' })
      }
      return jsonResponse({ userId: 'u1', username: 'owner' })
    })

    await refreshCsrf()
    const user = await auth.login('owner', 'secret')

    expect(user.userId).toBe('u1')
    // The login dropped the old token and fetched a new one, so a later request cannot present a
    // token that belonged to the pre-login session.
    expect(csrfIndex).toBe(2)
  })

  it('builds content and poster URLs under the API prefix', () => {
    expect(media.contentUrl('m1')).toBe('/api/v1/media/m1/content')
    expect(media.posterUrl('m1')).toBe('/api/v1/media/m1/poster')
  })

  it('encodes the search term into the media list request', async () => {
    let requestedUrl = null
    vi.stubGlobal('fetch', async (url) => {
      requestedUrl = url
      return jsonResponse({ items: [], page: 1, pageSize: 20, total: 0 })
    })

    await media.list('s1', { q: '团队 活动', page: 2, pageSize: 10 })

    expect(requestedUrl).toContain('/spaces/s1/media?')
    expect(requestedUrl).toContain('page=2')
    expect(requestedUrl).toContain('pageSize=10')
    // The space and the term are percent-encoded, so a space in a title cannot change the query.
    expect(requestedUrl).toContain(`q=${encodeURIComponent('团队 活动')}`)
  })

  it('sends the idempotency key when a retry is requested', async () => {
    let sentHeaders = null
    vi.stubGlobal('fetch', async (url, options) => {
      if (url.endsWith('/auth/csrf')) {
        return jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' })
      }
      sentHeaders = options.headers
      return jsonResponse({ taskId: 't1', generation: 2, state: 'WAITING_EVENT' }, { status: 202 })
    })

    await tasks.retry('t1', 'key-12345678')

    expect(sentHeaders['Idempotency-Key']).toBe('key-12345678')
  })

  it('generates distinct idempotency keys', () => {
    const keys = new Set(Array.from({ length: 50 }, () => newIdempotencyKey()))
    expect(keys.size).toBe(50)
  })
})
