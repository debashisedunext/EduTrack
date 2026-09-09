import { Outlet } from 'react-router-dom'

import { usePortalAuthStore } from './auth/portalAuthStore'
import { usePortalSignOut } from './auth/usePortalSignOut'

/**
 * CP-03/CP-04's "own minimal shell" (plan §9) — a top bar and nothing else.
 * No sidebar, no project switcher, no notification bell: those query staff
 * endpoints and mean nothing to a customer, and a portal with the staff
 * shell's chrome around it is the confusion plan §11's route-tree fork
 * exists to prevent from the other direction too.
 */
export function PortalShell() {
  const client = usePortalAuthStore((state) => state.client)
  const signOut = usePortalSignOut()

  return (
    <div className="min-h-screen bg-app">
      <header className="flex items-center justify-between border-b border-border bg-surface px-6 py-3">
        <div className="flex items-center gap-2 text-base font-bold text-content">
          <span
            aria-hidden="true"
            className="flex size-7 items-center justify-center rounded-[8px] bg-primary text-sm font-extrabold text-white"
          >
            E
          </span>
          EduTrack
        </div>
        <div className="flex items-center gap-4 text-sm text-content-muted">
          {client?.displayName ? <span>{client.displayName}</span> : null}
          <button
            type="button"
            onClick={signOut}
            className="rounded-control px-2.5 py-1.5 font-medium text-primary hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            Sign out
          </button>
        </div>
      </header>
      <main className="mx-auto max-w-5xl px-4 py-6 sm:px-6">
        <Outlet />
      </main>
    </div>
  )
}
