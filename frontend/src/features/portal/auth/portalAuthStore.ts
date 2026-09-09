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
 * No `'unknown'` state: with no cookie and no refresh, there is nothing to
 * check on a fresh page load — a reload is always an anonymous visitor.
 * (C-121's original store modelled a startup restore against a refresh
 * endpoint that A-130 never built.)
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

export const usePortalAuthStore = create<PortalAuthState>((set) => ({
  ...initialPortalAuthState,

  signIn: (session) => {
    setAccessToken(session.accessToken)
    set({
      status: 'authenticated',
      client: session.client,
      expiresAt: Date.now() + session.expiresIn * 1000,
    })
  },

  signOut: () => {
    // Clear the token before the state update, exactly as `authStore.signOut`
    // does and for the same reason: a re-render against 'anonymous' state
    // must never find `http.ts` still holding a live token to refetch with.
    setAccessToken(null)
    set({ status: 'anonymous', client: null, expiresAt: null })
  },
}))
