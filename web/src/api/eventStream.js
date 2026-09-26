/**
 * Server-sent events over a fetch stream.
 *
 * The browser's EventSource cannot send a CSRF header and reports a failure as an opaque error with
 * no status, which makes a permission problem indistinguishable from a network problem. Reading the
 * response body as a stream keeps the session cookie authentication and gives the caller the real
 * status when the connection is refused.
 *
 * A disconnection is reported as a disconnection, never as a task outcome: the caller keeps polling
 * the authoritative endpoint, because the event stream is a convenience and the task row is the
 * answer.
 */

/** Splits a text stream into SSE frames, buffering partial frames between reads. */
function createFrameParser() {
  let buffer = ''
  return {
    /** @returns {Array<{id: string|null, event: string, data: string}>} complete frames */
    push(chunk) {
      buffer += chunk
      const frames = []
      let separator = buffer.indexOf('\n\n')
      while (separator !== -1) {
        const raw = buffer.slice(0, separator)
        buffer = buffer.slice(separator + 2)
        const frame = { id: null, event: 'message', data: '' }
        const dataLines = []
        let hasEventField = false
        for (const line of raw.split('\n')) {
          if (line.startsWith(':')) {
            continue // a comment, used as a heartbeat
          }
          const colon = line.indexOf(':')
          const field = colon === -1 ? line : line.slice(0, colon)
          const value = colon === -1 ? '' : line.slice(colon + 1).replace(/^ /, '')
          if (field === 'id') {
            frame.id = value
            hasEventField = true
          } else if (field === 'event') {
            frame.event = value
            hasEventField = true
          } else if (field === 'data') {
            dataLines.push(value)
            hasEventField = true
          }
        }
        frame.data = dataLines.join('\n')
        if (hasEventField) {
          frames.push(frame)
        }
        separator = buffer.indexOf('\n\n')
      }
      return frames
    }
  }
}

/**
 * Opens a stream and invokes a callback for every frame.
 *
 * @param {string} url endpoint returning text/event-stream
 * @param {(frame: {id: string|null, event: string, data: string}) => void} onFrame
 * @param {(state: {status: 'open'|'closed'|'error', httpStatus?: number}) => void} onState
 * @returns {{close: () => void}}
 */
export function openEventStream(url, onFrame, onState = () => {}) {
  const controller = new AbortController()
  let closed = false

  async function run() {
    // A stream that ends is retried after a pause, unless the caller closed it. The delay is fixed
    // rather than exponential because the caller is also polling, so this path is not the only way
    // the interface stays current.
    while (!closed) {
      try {
        const response = await fetch(url, {
          credentials: 'same-origin',
          headers: { Accept: 'text/event-stream' },
          signal: controller.signal
        })
        if (!response.ok || !response.body) {
          onState({ status: 'error', httpStatus: response.status })
          if (response.status === 401 || response.status === 403 || response.status === 404) {
            return // a permission or existence answer will not change by retrying
          }
          await pause(5000)
          continue
        }
        onState({ status: 'open' })
        const parser = createFrameParser()
        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        while (!closed) {
          const { value, done } = await reader.read()
          if (done) {
            break
          }
          for (const frame of parser.push(decoder.decode(value, { stream: true }))) {
            onFrame(frame)
          }
        }
        if (!closed) {
          onState({ status: 'closed' })
          await pause(2000)
        }
      } catch (error) {
        if (closed || error.name === 'AbortError') {
          return
        }
        onState({ status: 'error' })
        await pause(5000)
      }
    }
  }

  function pause(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds))
  }

  run()

  return {
    close() {
      closed = true
      controller.abort()
    }
  }
}
