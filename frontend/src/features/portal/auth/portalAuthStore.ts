import { create } from 'zustand'

import { setAccessToken } from '@/api/http'
import type { PortalSession } from '@/api/generated/model/portalSession'

/**
 * C-121 · who is signed in to the client portal — {@code authStore.ts}'s
 * shape, one principal type over.
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
 * ## No idle/absolute timeout
 *
 * A-025's 30-minute idle and 12-hour absolute limits are a staff session
 * concern this task was not asked to reproduce for the portal, and the
 * backend's own `PortalRefreshTokenStore` does not yet implement the
 * device-family tracking those limits would need to mean anything client
 * side. This store only renews before expiry — see `PortalAuthProvider`.
 */

export type PortalAuthStatus = 'unknown' | 'authenticated' | 'anonymous'

interface PortalAuthState {
  status: PortalAuthStatus
  user: PortalSession['user'] | null
  mustChangePassword: boolean
  expiresAt: number | null

  signIn: (session: PortalSession) => void
  signOut: () => void
  clearPasswordChangeRequirement: () => void
}

export const initialPortalAuthState = {
  status: 'unknown' as PortalAuthStatus,
  user: null,
  mustChangePassword: false,
  expiresAt: null,
}

export const usePortalAuthStore = create<PortalAuthState>((set) => ({
  ...initialPortalAuthState,

  signIn: (session) => {
    setAccessToken(session.accessToken)
    set({
      status: 'authenticated',
      user: session.user,
      mustChangePassword: session.mustChangePassword ?? false,
      expiresAt: Date.now() + session.expiresIn * 1000,
    })
  },

  signOut: () => {
    // Clear the token before the state update, exactly as `authStore.signOut`
    // does and for the same reason: a re-render against 'anonymous' state
    // must never find `http.ts` still holding a live token to refetch with.
    setAccessToken(null)
    set({ status: 'anonymous', user: null, mustChangePassword: false, expiresAt: null })
  },

  clearPasswordChangeRequirement: () => set({ mustChangePassword: false }),
}))
