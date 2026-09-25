import { afterEach, describe, expect, it, vi } from 'vitest'
import { openEventStream } from './eventStream'

/**
 * The stream reader must reassemble frames that arrive split across reads, ignore heartbeats, and
 * report a refusal with its status. A frame boundary landing in the middle of a chunk is the normal
 * case over a network, not an edge case.
 */
function streamResponse(text, { status = 200, ok = true } = {}) {
  const encoder = new TextEncoder()
  const chunks = Array.isArray(text) ? text : [text]
  let index = 0
  return {
    ok,
    status,
    body: {
      getReader: () => ({
        read: async () => {
          if (index >= chunks.length) {
            return { done: true, value: undefined }
          }
          return { done: false, value: encoder.encode(chunks[index++]) }
        }
      })
    }
  }
}

describe('event stream', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('parses a complete frame', async () => {
    const frames = []
    vi.stubGlobal('fetch', async () =>
      streamResponse(
        'id: 7\nevent: state\ndata: {"taskId":"t1","state":"RUNNING","progress":37,"version":7}\n\n'
      )
    )

    const stream = openEventStream('/events', (frame) => frames.push(frame))
    await vi.waitFor(() => expect(frames.length).toBe(1))
    stream.close()

    expect(frames[0].id).toBe('7')
    expect(frames[0].event).toBe('state')
    expect(JSON.parse(frames[0].data)).toMatchObject({ state: 'RUNNING', progress: 37, version: 7 })
  })

  it('reassembles a frame split across two reads', async () => {
    const frames = []
    vi.stubGlobal('fetch', async () =>
      streamResponse(['id: 9\nevent: snapshot\nda', 'ta: {"version":9,"state":"QUEUED"}\n\n'])
    )

    const stream = openEventStream('/events', (frame) => frames.push(frame))
    await vi.waitFor(() => expect(frames.length).toBe(1))
    stream.close()

    expect(frames[0].id).toBe('9')
    expect(JSON.parse(frames[0].data).state).toBe('QUEUED')
  })

  it('ignores a comment used as a heartbeat', async () => {
    const frames = []
    vi.stubGlobal('fetch', async () => streamResponse(':heartbeat\n\ndata: {"version":2}\n\n'))

    const stream = openEventStream('/events', (frame) => frames.push(frame))
    await vi.waitFor(() => expect(frames.length).toBe(1))
    stream.close()

    // Only the data frame surfaced; the heartbeat produced no event for the caller to reason about.
    expect(frames).toHaveLength(1)
    expect(frames[0].event).toBe('message')
  })

  it('reports the status when the stream is refused and stops retrying a permission answer', async () => {
    const states = []
    let fetches = 0
    vi.stubGlobal('fetch', async () => {
      fetches += 1
      return streamResponse('', { status: 404, ok: false })
    })

    const stream = openEventStream(
      '/events',
      () => {},
      (state) => states.push(state)
    )
    await vi.waitFor(() => expect(states.length).toBeGreaterThan(0))
    stream.close()

    expect(states[0]).toEqual({ status: 'error', httpStatus: 404 })
    // A 404 is an answer, not a transient fault: retrying it would poll forever for a task that
    // does not exist.
    expect(fetches).toBe(1)
  })

  it('stops reading once closed', async () => {
    const frames = []
    vi.stubGlobal('fetch', async () =>
      streamResponse(['data: {"version":1}\n\n', 'data: {"version":2}\n\n'])
    )

    const stream = openEventStream('/events', (frame) => frames.push(frame))
    await vi.waitFor(() => expect(frames.length).toBeGreaterThan(0))
    stream.close()
    const countAtClose = frames.length
    await new Promise((resolve) => setTimeout(resolve, 30))

    expect(frames.length).toBe(countAtClose)
  })
})
