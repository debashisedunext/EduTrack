import { Navigate, Outlet, useLocation } from 'react-router-dom'

import { usePortalAuthStore } from './portalAuthStore'

/**
 * A-130 · the gate in front of every authenticated portal route —
 * `RequireAuth`'s shape, one principal type over.
 *
 * Convenience only, exactly as the staff guard's own note says: the real
 * boundary is server-side (`ClientPrincipal`). Hiding a route here stops an
 * honest client landing on a screen that would only 401, nothing more.
 *
 * No `'unknown'` splash state — see `portalAuthStore`'s own note: a portal
 * session is never restored on load, so the status is always known
 * synchronously. No forced-password-change redirect either — A-130's
 * redemption flow is the whole story for that (see `PortalOnboardingController`'s
 * javadoc); there is no must-change state left for this guard to police.
 */
export function PortalRequireAuth() {
  const status = usePortalAuthStore((state) => state.status)
  const location = useLocation()

  if (status === 'anonymous') {
    return <Navigate to="/portal/login" replace state={{ from: location }} />
  }

  return <Outlet />
}
