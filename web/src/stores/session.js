import { reactive, readonly } from 'vue'
import { auth, ApiError } from '../api/client'

/**
 * The signed-in user, held in one reactive object.
 *
 * This is a convenience for the interface, not a security boundary. Whether an action is allowed is
 * decided by the server on every request; hiding a control the user may not use is a usability
 * choice, and the client must still handle the 403 the server would return.
 */
const state = reactive({
  user: null,
  checked: false,
  spaceId: null
})

export const session = readonly(state)

/** Resolves the current identity. Called once when the application starts. */
export async function ensureIdentity() {
  if (state.checked) {
    return state.user
  }
  try {
    state.user = await auth.me()
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) {
      // A network failure is not the same as being signed out; the caller decides what to show.
      throw error
    }
    state.user = null
  }
  state.checked = true
  return state.user
}

export async function signIn(username, password) {
  state.user = await auth.login(username, password)
  state.checked = true
  return state.user
}

export async function signOut() {
  try {
    await auth.logout()
  } finally {
    // Local state is cleared even when the call failed, so the interface does not keep showing a
    // session that the user asked to end.
    state.user = null
    state.spaceId = null
    state.checked = true
  }
}

/** Remembers the space the user is working in. */
export function rememberSpace(spaceId) {
  state.spaceId = spaceId
}
