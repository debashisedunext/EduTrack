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
 * synchronously.
 */
export function PortalRequireAuth() {
  const status = usePortalAuthStore((state) => state.status)
  const mustChangePassword = usePortalAuthStore((state) => state.mustChangePassword)
  const location = useLocation()

  if (status === 'anonymous') {
    return <Navigate to="/portal/login" replace state={{ from: location }} />
  }

  /*
    The session is valid and every portal route except the change-password
    screen is closed until the temporary password is replaced. Enforced here
    rather than per-page, for `RequireAuth`'s reason one principal type over:
    a check on the module chooser alone leaves `/portal/onboarding` open to
    anyone who types the URL, and `PortalPasswordChangeGate` would then be the
    only thing stopping them — which shows up as an unexplained error page
    instead of a form.

    No `from` state, unlike the anonymous branch above. A client who has not
    chosen a password was not going anywhere in particular; they had just
    signed in. Sending them onward afterwards would be guessing.
  */
  if (mustChangePassword && location.pathname !== '/portal/change-password') {
    return <Navigate to="/portal/change-password" replace />
  }

  return <Outlet />
}
