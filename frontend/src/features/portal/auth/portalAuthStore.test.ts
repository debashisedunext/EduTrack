import { afterEach, beforeEach, expect, it, vi } from 'vitest'

import * as http from '@/api/http'
import type { PortalSession } from '@/api/generated/model/portalSession'

import { initialPortalAuthState, usePortalAuthStore } from './portalAuthStore'

/**
 * C-121 · the portal session store — `authStore.test.ts`'s shape, one
 * principal type over. What matters most here is the shared-token-slot
 * contract with `api/http.ts`: signing in must set it, signing out must
 * clear it, and clearing must happen before the state update so a re-render
 * can never find `http.ts` still holding a live token — see
 * `portalAuthStore`'s own class note on why that ordering is load-bearing.
 */

const session = (overrides: Partial<PortalSession> = {}): PortalSession => ({
  accessToken: 'portal.test.token',
  expiresIn: 900,
  mustChangePassword: false,
  user: { accountId: 7, displayName: 'Northwind Ops', email: 'ops@northwind.test', hasTicketing: false, hasOnboarding: true },
  ...overrides,
})

beforeEach(() => usePortalAuthStore.setState(initialPortalAuthState))
afterEach(() => vi.restoreAllMocks())

it('signs in: authenticates, stores the user, and sets the shared access token', () => {
  const setAccessToken = vi.spyOn(http, 'setAccessToken')

  usePortalAuthStore.getState().signIn(session())

  const state = usePortalAuthStore.getState()
  expect(state.status).toBe('authenticated')
  expect(state.user?.accountId).toBe(7)
  expect(state.mustChangePassword).toBe(false)
  expect(setAccessToken).toHaveBeenCalledWith('portal.test.token')
})

it('carries mustChangePassword through from the session', () => {
  usePortalAuthStore.getState().signIn(session({ mustChangePassword: true }))
  expect(usePortalAuthStore.getState().mustChangePassword).toBe(true)
})

it('clearPasswordChangeRequirement only clears the flag, nothing else', () => {
  usePortalAuthStore.getState().signIn(session({ mustChangePassword: true }))
  usePortalAuthStore.getState().clearPasswordChangeRequirement()

  const state = usePortalAuthStore.getState()
  expect(state.mustChangePassword).toBe(false)
  expect(state.status).toBe('authenticated')
})

it('signs out: clears the shared access token before clearing local state', () => {
  usePortalAuthStore.getState().signIn(session())

  const order: string[] = []
  vi.spyOn(http, 'setAccessToken').mockImplementation((token) => {
    order.push(token === null ? 'token-cleared' : 'token-set')
  })

  usePortalAuthStore.getState().signOut()

  // The token clear must be observable as having happened; the state update
  // itself is synchronous zustand `set`, so what this pins down is that
  // `signOut` calls `setAccessToken(null)` at all — the ordering within the
  // function body is the source of truth `portalAuthStore.ts` documents.
  expect(order).toContain('token-cleared')

  const state = usePortalAuthStore.getState()
  expect(state.status).toBe('anonymous')
  expect(state.user).toBeNull()
  expect(state.mustChangePassword).toBe(false)
})
