import * as React from 'react'

import { restorePortalSession, usePortalAuthStore } from './portalAuthStore'

/**
 * A-130 · the portal session's clock and its startup restore —
 * `AuthProvider`'s shape, mounted only under `/portal/**` rather than above
 * the whole app, so a staff page never mounts portal-only state it has no
 * reason to hold.
 *
 * A-130 issues one access token with no refresh cookie and no rotation, so
 * there is no renewal to schedule. Two jobs remain:
 *
 * 1. **Restore the session a reload would otherwise lose.** Done during
 *    render rather than in an effect, and only here — see
 *    {@link restorePortalSession} for why both of those matter.
 * 2. **End the session locally once the token's lifetime is up**, so
 *    `PortalRequireAuth` redirects to sign-in instead of letting every
 *    subsequent call start silently failing with `401`.
 */
export function PortalAuthProvider({ children }: { children: React.ReactNode }) {
  const signOut = usePortalAuthStore((state) => state.signOut)
  const status = usePortalAuthStore((state) => state.status)
  const expiresAt = usePortalAuthStore((state) => state.expiresAt)

  // Before the first child renders, so the first query already carries the
  // token and `PortalRequireAuth` reads a settled status. `useState`'s
  // initialiser runs exactly once per mount and its return value is unused —
  // it is the "run this synchronously, once" hook, not state anybody reads.
  React.useState(() => {
    restorePortalSession()
    return null
  })

  React.useEffect(() => {
    if (status !== 'authenticated' || expiresAt === null) return

    const delay = Math.max(0, expiresAt - Date.now())
    const timer = setTimeout(signOut, delay)

    return () => clearTimeout(timer)
  }, [status, expiresAt, signOut])

  return <>{children}</>
}
