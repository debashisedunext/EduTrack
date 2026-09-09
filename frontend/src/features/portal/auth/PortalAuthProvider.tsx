import * as React from 'react'

import { usePortalAuthStore } from './portalAuthStore'

/**
 * A-130 · the portal session's clock — `AuthProvider`'s shape, mounted only
 * under `/portal/**` rather than above the whole app, so a staff page never
 * mounts portal-only state it has no reason to hold.
 *
 * A-130 issues one access token with no refresh cookie and no rotation: a
 * portal session lasts exactly one access-token lifetime. So unlike the
 * staff `AuthProvider`, there is no startup restore (nothing survives a
 * reload — see `portalAuthStore`'s own note) and no renewal to schedule.
 * The one job left is ending the session locally once the token's lifetime
 * is up, so `PortalRequireAuth` redirects to sign-in instead of letting
 * every subsequent call start silently failing with `401`.
 */
export function PortalAuthProvider({ children }: { children: React.ReactNode }) {
  const signOut = usePortalAuthStore((state) => state.signOut)
  const status = usePortalAuthStore((state) => state.status)
  const expiresAt = usePortalAuthStore((state) => state.expiresAt)

  React.useEffect(() => {
    if (status !== 'authenticated' || expiresAt === null) return

    const delay = Math.max(0, expiresAt - Date.now())
    const timer = setTimeout(signOut, delay)

    return () => clearTimeout(timer)
  }, [status, expiresAt, signOut])

  return <>{children}</>
}
