import * as React from 'react'

import { portalLogout, portalRefreshSession } from '@/api/generated/portal/portal'

import { usePortalAuthStore } from './portalAuthStore'

/**
 * C-121 · the portal session's clock — `AuthProvider`'s shape, mounted only
 * under `/portal/**` rather than above the whole app, so a staff page never
 * spends a `POST /portal/auth/refresh` it has no reason to make.
 *
 * No idle/absolute timeout renewal logic — see `portalAuthStore`'s own note
 * on why that is not reproduced here. This restores the session after a
 * reload and renews the access token ahead of its 15-minute expiry, nothing
 * more.
 */

const RENEW_MARGIN_MS = 60_000
const MIN_RENEW_DELAY_MS = 5_000

/**
 * One startup refresh per mount of the portal tree, guarded the same way
 * `AuthProvider`'s own module-level promise is — React StrictMode's double
 * invocation must not replay a consumed rotation token.
 */
let startupRefreshInFlight: ReturnType<typeof portalRefreshSession> | null = null

function startupRefresh(): ReturnType<typeof portalRefreshSession> {
  startupRefreshInFlight ??= portalRefreshSession()
  return startupRefreshInFlight
}

export function PortalAuthProvider({ children }: { children: React.ReactNode }) {
  const signIn = usePortalAuthStore((state) => state.signIn)
  const signOut = usePortalAuthStore((state) => state.signOut)
  const status = usePortalAuthStore((state) => state.status)
  const expiresAt = usePortalAuthStore((state) => state.expiresAt)

  const endSession = React.useCallback(
    (notifyServer = true) => {
      if (notifyServer) {
        portalLogout().catch(() => {
          /* best effort — the local clear below is what the user sees */
        })
      }
      signOut()
    },
    [signOut],
  )

  React.useEffect(() => {
    let cancelled = false
    startupRefresh()
      .then((response) => {
        if (!cancelled) signIn(response.data)
      })
      .catch(() => {
        if (!cancelled) signOut()
      })
    return () => {
      cancelled = true
    }
  }, [signIn, signOut])

  React.useEffect(() => {
    if (status !== 'authenticated' || expiresAt === null) return

    const delay = Math.max(MIN_RENEW_DELAY_MS, expiresAt - Date.now() - RENEW_MARGIN_MS)
    const timer = setTimeout(() => {
      portalRefreshSession()
        .then((response) => signIn(response.data))
        .catch(() => endSession(false))
    }, delay)

    return () => clearTimeout(timer)
  }, [status, expiresAt, endSession, signIn])

  return <>{children}</>
}
