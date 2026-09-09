import { Navigate, Outlet, useLocation } from 'react-router-dom'

import { usePortalAuthStore } from './portalAuthStore'

/**
 * C-121 · the gate in front of every authenticated portal route —
 * `RequireAuth`'s shape, one principal type over.
 *
 * Convenience only, exactly as the staff guard's own note says: row scoping
 * and the forced-password gate are decided server-side
 * (`PortalPasswordChangeGate`, `ClientPrincipal`). Hiding a route here stops
 * an honest client landing on a screen that would only 403/401, nothing more.
 */
export function PortalRequireAuth() {
  const status = usePortalAuthStore((state) => state.status)
  const mustChangePassword = usePortalAuthStore((state) => state.mustChangePassword)
  const location = useLocation()

  if (status === 'unknown') return <PortalSessionSplash />

  if (status === 'anonymous') {
    return <Navigate to="/portal/login" replace state={{ from: location }} />
  }

  if (mustChangePassword && location.pathname !== '/portal/set-password') {
    return <Navigate to="/portal/set-password" replace />
  }

  return <Outlet />
}

function PortalSessionSplash() {
  return (
    <div
      className="flex min-h-screen items-center justify-center bg-app"
      role="status"
      aria-live="polite"
    >
      <span className="sr-only">Restoring your session</span>
    </div>
  )
}
