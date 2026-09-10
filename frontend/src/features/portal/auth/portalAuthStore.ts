import { create } from 'zustand'

import { setAccessToken } from '@/api/http'
import type { PortalClient } from '@/api/generated/model/portalClient'
import type { PortalLoginResult } from '@/api/generated/model/portalLoginResult'

/**
 * A-130 · who is signed in to the client portal — `authStore.ts`'s shape,
 * one principal type over.
 *
 * ## The shared token slot, and why this is safe
 *
 * `api/http.ts` (Stream D's, D-003) holds exactly one module-level access
 * token behind `setAccessToken`/`getAccessToken`, used by every generated
 * call regardless of which tag it came from. This store calls the same
 * exported function `authStore.ts` calls — consuming a public export, not
 * editing the file, which this task's own boundary note treats as the line
 * between the two.
 *
 * That is safe only because the staff shell and the portal shell are
 * mutually exclusive route trees that are never mounted together: signing in
 * as staff happens on `/login`, signing in to the portal on `/portal/login`,
 * and nothing in this build lets one tab hold both sessions live at once. If
 * that ever changes — an admin "view as client" preview, say — the single
 * slot in `http.ts` stops being enough and needs a second one, keyed by
 * principal type. Flagged here rather than worked around, since `http.ts` is
 * not this stream's file to widen unilaterally.
 *
 * ## No refresh, no idle/absolute timeout
 *
 * A-130 issues a single access token with no refresh cookie and no rotation
 * — a portal session lasts one access-token lifetime, full stop. There is
 * nothing here to renew: when `expiresAt` passes, the session simply ends
 * and the client signs in again. (C-121's original store here modelled a
 * refresh cycle against a backend that never shipped one; A-130's real
 * surface has none, so neither does this store.)
 */

/**
 * Still no `'unknown'` state, and the reason has changed. There is nothing
 * asynchronous to wait for on load: {@link restorePortalSession} reads
 * `sessionStorage` synchronously, so the status is settled before the first
 * render and `PortalRequireAuth` never has to guess.
 */
export type PortalAuthStatus = 'authenticated' | 'anonymous'

interface PortalAuthState {
  status: PortalAuthStatus
  client: PortalClient | null
  expiresAt: number | null

  signIn: (session: PortalLoginResult) => void
  signOut: () => void
}

export const initialPortalAuthState = {
  status: 'anonymous' as PortalAuthStatus,
  client: null,
  expiresAt: null,
}

/**
 * Where a live portal session survives a reload.
 *
 * ## Why this holds a bearer token, when `authStore` refuses to
 *
 * `authStore.ts` argues at length against putting the staff access token in
 * web storage, and it is right: for staff there is a strictly better option
 * — an HttpOnly refresh cookie the page cannot read, which `AuthProvider`
 * spends one `POST /auth/refresh` on at startup. Storage there would be a
 * downgrade taken for convenience.
 *
 * **The portal has no such option.** A-130 issues one access token, no
 * refresh cookie, no rotation, and `/portal/auth` exposes no refresh route
 * to add one from here. So the real choice is not "cookie or storage", it is
 * "storage or a portal that signs the client out every time they reload,
 * press back, or follow a link out and return" — which is what shipped, and
 * which reads to a client as the product being broken.
 *
 * Narrowed as far as it can be without a server change:
 *
 * - **`sessionStorage`, never `localStorage`** — scoped to the one tab and
 *   dropped when it closes, so a shared machine does not keep the session.
 * - **The stored expiry is enforced on read**, so a token past its lifetime
 *   is discarded rather than replayed into a guaranteed `401`.
 * - **Written only by the portal's own sign-in**, and removed by sign-out
 *   and by any unreadable or malformed entry.
 *
 * The XSS exposure `authStore` warns about is real and is not argued away
 * here: a script injected into the portal origin could read this. The
 * durable fix is a portal refresh cookie so this file can go back to holding
 * the token in memory only — worth raising against A-130 rather than
 * papering over here.
 */
const SESSION_KEY = 'edutrack.portal.session'

interface StoredPortalSession {
  accessToken: string
  client: PortalClient
  expiresAt: number
}

/**
 * Never throws. Storage is unavailable in more situations than it looks —
 * Safari private browsing, an embedded webview, a browser configured to
 * block site data — and a portal that crashed on boot for a client with
 * cookies disabled would be a worse bug than the one this fixes.
 */
function readStoredSession(): StoredPortalSession | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<StoredPortalSession>
    if (typeof parsed?.accessToken !== 'string' || typeof parsed?.expiresAt !== 'number' || !parsed.client) {
      return null
    }
    return parsed as StoredPortalSession
  } catch {
    return null
  }
}

function writeStoredSession(session: StoredPortalSession): void {
  try {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
  } catch {
    // A session that cannot be persisted still works for this page's
    // lifetime; it just will not survive the reload. Failing the sign-in
    // over it would be strictly worse.
  }
}

function clearStoredSession(): void {
  try {
    sessionStorage.removeItem(SESSION_KEY)
  } catch {
    // Nothing to do: if it cannot be removed it could not have been written.
  }
}

export const usePortalAuthStore = create<PortalAuthState>((set) => ({
  ...initialPortalAuthState,

  signIn: (session) => {
    const expiresAt = Date.now() + session.expiresIn * 1000
    setAccessToken(session.accessToken)
    writeStoredSession({ accessToken: session.accessToken, client: session.client, expiresAt })
    set({ status: 'authenticated', client: session.client, expiresAt })
  },

  signOut: () => {
    // Clear the token before the state update, exactly as `authStore.signOut`
    // does and for the same reason: a re-render against 'anonymous' state
    // must never find `http.ts` still holding a live token to refetch with.
    setAccessToken(null)
    clearStoredSession()
    set({ status: 'anonymous', client: null, expiresAt: null })
  },
}))

/**
 * Puts a stored session back into the store and into `http.ts`'s token slot.
 *
 * <b>Call this from the portal subtree only, and during render rather than
 * in an effect.</b> Both halves matter:
 *
 * - `http.ts` holds one process-wide token shared with the staff shell, so
 *   restoring unconditionally at module load would let merely importing this
 *   file overwrite a staff session's token. `PortalAuthProvider` is mounted
 *   under `/portal/**` and nowhere else, which is the scope this belongs to.
 * - The first portal query fires on the first render. Restoring in an effect
 *   would run after it, so the reload would still produce one anonymous
 *   `401` and a redirect to sign-in — the exact bug this exists to fix.
 *
 * Idempotent: a store already authenticated is left alone, so a re-render or
 * a second provider mount cannot clobber a fresher session.
 */
export function restorePortalSession(): void {
  if (usePortalAuthStore.getState().status === 'authenticated') return

  const stored = readStoredSession()
  if (!stored) return

  if (stored.expiresAt <= Date.now()) {
    clearStoredSession()
    return
  }

  setAccessToken(stored.accessToken)
  usePortalAuthStore.setState({
    status: 'authenticated',
    client: stored.client,
    expiresAt: stored.expiresAt,
  })
}
