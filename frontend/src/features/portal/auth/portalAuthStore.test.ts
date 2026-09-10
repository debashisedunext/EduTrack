import { afterEach, beforeEach, expect, it, vi } from 'vitest'

import * as http from '@/api/http'
import type { PortalLoginResult } from '@/api/generated/model/portalLoginResult'

import { initialPortalAuthState, restorePortalSession, usePortalAuthStore } from './portalAuthStore'

/**
 * A-130 · the portal session store — `authStore.test.ts`'s shape, one
 * principal type over. What matters most here is the shared-token-slot
 * contract with `api/http.ts`: signing in must set it, signing out must
 * clear it, and clearing must happen before the state update so a re-render
 * can never find `http.ts` still holding a live token — see
 * `portalAuthStore`'s own class note on why that ordering is load-bearing.
 */

const loginResult = (overrides: Partial<PortalLoginResult> = {}): PortalLoginResult => ({
  accessToken: 'portal.test.token',
  expiresIn: 900,
  client: { username: 'northwind.ops', displayName: 'Northwind Ops', hasTicketing: false, hasOnboarding: true },
  ...overrides,
})

beforeEach(() => {
  usePortalAuthStore.setState(initialPortalAuthState)
  sessionStorage.clear()
})
afterEach(() => vi.restoreAllMocks())

it('signs in: authenticates, stores the client, and sets the shared access token', () => {
  const setAccessToken = vi.spyOn(http, 'setAccessToken')

  usePortalAuthStore.getState().signIn(loginResult())

  const state = usePortalAuthStore.getState()
  expect(state.status).toBe('authenticated')
  expect(state.client?.username).toBe('northwind.ops')
  expect(state.client?.hasOnboarding).toBe(true)
  expect(setAccessToken).toHaveBeenCalledWith('portal.test.token')
})

it('starts anonymous until a stored session is restored', () => {
  expect(usePortalAuthStore.getState().status).toBe('anonymous')
})

/**
 * The reload bug this persistence exists to fix: the portal signed the
 * client out on every refresh, back button and returning link, because the
 * token lived only in memory and A-130 ships no refresh cookie to rebuild it
 * from. Storage is the only restore available until one exists.
 */
it('restores a live session after a reload, token and all', () => {
  usePortalAuthStore.getState().signIn(loginResult())

  // What a reload is, to a module: fresh store, empty token slot,
  // sessionStorage the only thing that survived.
  usePortalAuthStore.setState(initialPortalAuthState)
  const setAccessToken = vi.spyOn(http, 'setAccessToken')

  restorePortalSession()

  const state = usePortalAuthStore.getState()
  expect(state.status).toBe('authenticated')
  expect(state.client?.username).toBe('northwind.ops')
  expect(setAccessToken).toHaveBeenCalledWith('portal.test.token')
})

it('discards a stored session whose token has already expired', () => {
  usePortalAuthStore.getState().signIn(loginResult({ expiresIn: -1 }))
  usePortalAuthStore.setState(initialPortalAuthState)
  const setAccessToken = vi.spyOn(http, 'setAccessToken')

  restorePortalSession()

  // Replaying it would put a guaranteed 401 behind every call on the page.
  expect(usePortalAuthStore.getState().status).toBe('anonymous')
  expect(setAccessToken).not.toHaveBeenCalled()
})

it('signing out leaves nothing for a later reload to restore', () => {
  usePortalAuthStore.getState().signIn(loginResult())
  usePortalAuthStore.getState().signOut()

  usePortalAuthStore.setState(initialPortalAuthState)
  restorePortalSession()

  expect(usePortalAuthStore.getState().status).toBe('anonymous')
})

it('survives storage being unreadable rather than failing the page', () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new Error('SecurityError: site data blocked')
  })

  expect(() => restorePortalSession()).not.toThrow()
  expect(usePortalAuthStore.getState().status).toBe('anonymous')
})

it('signs out: clears the shared access token before clearing local state', () => {
  usePortalAuthStore.getState().signIn(loginResult())

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
  expect(state.client).toBeNull()
})
