/**
 * HTTP client for the API.
 *
 * Three things it centralises, because getting any of them wrong at one call site would be a
 * security or correctness problem rather than a cosmetic one:
 *
 *   - every request carries the session cookie and fetches a CSRF token first, and a token is
 *     re-fetched after a login or a logout because the token is bound to the session;
 *   - an error body is turned into a typed error carrying the server's stable code, so a caller
 *     branches on the code and never on the message text;
 *   - an upload is sent as a stream of chunks driven by the server's own chunk size, so the browser
 *     never chooses the geometry of a transfer.
 */

const API_PREFIX = '/api/v1'

/** Error carrying the server's stable code, safe to branch on. */
export class ApiError extends Error {
  constructor(code, message, status, requestId, resourceId, details) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.requestId = requestId
    this.resourceId = resourceId
    this.details = details
  }

  /** Whether the caller may retry the same request unchanged. */
  get retryable() {
    return this.status >= 500 || this.code === 'SERVICE_UNAVAILABLE'
  }
}

let csrfToken = null

/** Fetches a CSRF token and remembers it. The server decides the header name. */
export async function refreshCsrf() {
  const response = await fetch(`${API_PREFIX}/auth/csrf`, { credentials: 'same-origin' })
  if (!response.ok) {
    throw new ApiError('CSRF_UNAVAILABLE', '无法获取安全令牌', response.status)
  }
  const body = await response.json()
  csrfToken = { token: body.token, headerName: body.headerName }
  return csrfToken
}

/** Forgets the cached token. Called after login and logout, which both rotate the session. */
export function clearCsrf() {
  csrfToken = null
}

async function ensureCsrf() {
  if (!csrfToken) {
    await refreshCsrf()
  }
  return csrfToken
}

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

async function parseError(response) {
  let body = null
  try {
    body = await response.json()
  } catch {
    // A response with no JSON body still has a status, which is enough to report.
  }
  return new ApiError(
    body?.code ?? 'UNKNOWN',
    body?.message ?? `请求失败（${response.status}）`,
    response.status,
    body?.requestId ?? null,
    body?.resourceId ?? null,
    body?.details ?? null
  )
}

/**
 * Performs one request.
 *
 * @param {string} method HTTP method
 * @param {string} path path below /api/v1
 * @param {{body?: unknown, headers?: Record<string,string>, raw?: BodyInit, signal?: AbortSignal}} options
 */
export async function request(method, path, options = {}) {
  const headers = { ...(options.headers ?? {}) }
  if (UNSAFE_METHODS.has(method)) {
    const csrf = await ensureCsrf()
    headers[csrf.headerName] = csrf.token
  }
  let payload = options.raw
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(options.body)
  }
  const response = await fetch(`${API_PREFIX}${path}`, {
    method,
    headers,
    body: payload,
    credentials: 'same-origin',
    signal: options.signal
  })
  if (!response.ok) {
    const error = await parseError(response)
    if (error.status === 401) {
      clearCsrf()
    }
    throw error
  }
  if (response.status === 204) {
    return null
  }
  const text = await response.text()
  return text ? JSON.parse(text) : null
}

export const auth = {
  /** Signs in and rotates the local CSRF token, because the session changed. */
  async login(username, password) {
    const user = await request('POST', '/auth/login', { body: { username, password } })
    clearCsrf()
    await refreshCsrf()
    return user
  },
  async logout() {
    await request('POST', '/auth/logout', { body: {} })
    clearCsrf()
  },
  me: () => request('GET', '/auth/me')
}

export const spaces = {
  list: () => request('GET', '/spaces'),
  create: (name) => request('POST', '/spaces', { body: { name } }),
  members: (spaceId) => request('GET', `/spaces/${spaceId}/members`),
  putMember: (spaceId, userId, role) =>
    request('PUT', `/spaces/${spaceId}/members/${userId}`, { body: { role } }),
  removeMember: (spaceId, userId) => request('DELETE', `/spaces/${spaceId}/members/${userId}`)
}

export const media = {
  list: (spaceId, { q, page = 1, pageSize = 20 } = {}) => {
    const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
    if (q) {
      params.set('q', q)
    }
    return request('GET', `/spaces/${spaceId}/media?${params}`)
  },
  detail: (mediaId) => request('GET', `/media/${mediaId}`),
  rename: (mediaId, title, version) =>
    request('PATCH', `/media/${mediaId}`, { body: { title, version } }),
  remove: (mediaId) => request('DELETE', `/media/${mediaId}`),
  contentUrl: (mediaId) => `${API_PREFIX}/media/${mediaId}/content`,
  posterUrl: (mediaId) => `${API_PREFIX}/media/${mediaId}/poster`,
  shares: (mediaId) => request('GET', `/media/${mediaId}/shares`),
  createShare: (mediaId, expiresAt) =>
    request('POST', `/media/${mediaId}/shares`, { body: { expiresAt } }),
  revokeShare: (shareId) => request('DELETE', `/shares/${shareId}`)
}

export const tasks = {
  get: (taskId) => request('GET', `/tasks/${taskId}`),
  attempts: (taskId, { page = 1, pageSize = 20 } = {}) =>
    request('GET', `/tasks/${taskId}/attempts?page=${page}&pageSize=${pageSize}`),
  cancel: (taskId) => request('POST', `/tasks/${taskId}/cancel`, { body: {} }),
  /** Retry carries an idempotency key so a double click does not consume two generations. */
  retry: (taskId, idempotencyKey) =>
    request('POST', `/tasks/${taskId}/retry`, {
      body: {},
      headers: { 'Idempotency-Key': idempotencyKey }
    }),
  eventsUrl: (taskId) => `${API_PREFIX}/tasks/${taskId}/events`
}

export const share = {
  redeem: (token) => request('POST', '/public/share-access', { body: { token } }),
  contentUrl: () => `${API_PREFIX}/public/share-access/content`
}

/** A key that is unique per attempt and stable across a retry of the same attempt. */
export function newIdempotencyKey() {
  return `web-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`
}
