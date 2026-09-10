import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, waitFor } from '@testing-library/react'

/**
 * A-030 · the startup refresh, and the one route tree it must not run on.
 *
 * ## Why every case re-imports the module
 *
 * `AuthProvider.tsx` holds `startupRefreshInFlight` at module scope so that a
 * double-mounted effect (StrictMode, a remount) issues exactly one
 * `POST /auth/refresh` — consuming a second token from the family looks like
 * reuse under A-024's rotation. That singleton is deliberate and it outlives a
 * test, so a second `render` in the same module instance would resolve from the
 * cached promise and assert nothing. `vi.resetModules()` plus a dynamic import
 * gives each case its own.
 */
const refreshSession = vi.fn()
const logout = vi.fn()

vi.mock('@/api/generated/auth/auth', () => ({
  refreshSession: (...args: unknown[]) => refreshSession(...args),
  logout: (...args: unknown[]) => logout(...args),
}))

async function renderAt(pathname: string) {
  window.history.pushState({}, '', pathname)
  vi.resetModules()
  const { AuthProvider } = await import('./AuthProvider')
  return render(
    <AuthProvider>
      <p>shell</p>
    </AuthProvider>,
  )
}

describe('AuthProvider startup refresh', () => {
  beforeEach(() => {
    refreshSession.mockReset()
    logout.mockReset()
    refreshSession.mockRejectedValue(new Error('401'))
  })

  afterEach(() => {
    window.history.pushState({}, '', '/')
  })

  it('trades the refresh cookie for an access token on a staff route', async () => {
    await renderAt('/dashboard')

    await waitFor(() => expect(refreshSession).toHaveBeenCalledTimes(1))
  })

  /**
   * The regression this guard exists for.
   *
   * `api/http.ts` holds one process-wide access token shared by both shells,
   * and this provider is mounted above the router — so it mounts on portal
   * routes too. A 401 here is the normal answer for a portal client, and its
   * `.catch` signs out, which nulls the shared token that `PortalAuthProvider`
   * restored synchronously a moment earlier. The portal then renders its
   * skeleton forever against queries that all go out unauthenticated.
   *
   * Failing this test means a client reloading a portal page is signed out
   * without being told, which is invisible from the staff side of the app.
   */
  it('does not run on the client portal, where it would wipe the portal session', async () => {
    await renderAt('/portal/onboarding')

    // Given a beat for any effect to fire, it still must not have.
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(refreshSession).not.toHaveBeenCalled()
  })

  it('leaves the portal alone on every route beneath it, not just the index', async () => {
    await renderAt('/portal/onboarding/prerequisites/12')

    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(refreshSession).not.toHaveBeenCalled()
  })
})
