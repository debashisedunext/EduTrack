import { Outlet, useLocation } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'
import { CommandPalette } from './CommandPalette'
import { ErrorBoundary } from '@/components/ui/error-boundary'
import { Toaster } from '@/components/ui/toaster'
// D-043/D-044 (Stream D). Renders nothing — it subscribes the shell to the
// user's own queue so notifications toast and the bell badge stays live.
// Mounted here because it must outlive every route, and the Toaster it feeds
// is already at this level.
import { NotificationStream } from '@/features/notifications/NotificationStream'

// The chrome every screen renders inside — blueprint §7.2. Owned by C-005.
export function AppShell() {
  const location = useLocation()
  return (
    /*
      BUG-001 · `fixed inset-0`, not `h-screen`. `h-screen` is 100vh of a
      document that anything at body level can make taller — and when that
      happened the shell rendered as a 100vh band at the top of a taller page,
      with a second, document-level scrollbar beside `<main>`'s and dead
      background below it. Fixed to the viewport the shell always fills the
      window exactly and adds no height to the document, so `<main>` is the
      only place the app scrolls. Auth screens render outside the shell and
      still scroll the document normally.
    */
    <div className="fixed inset-0 flex overflow-hidden bg-app">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar />
        <main className="flex-1 overflow-y-auto">
          {/*
            No error boundary existed anywhere in the app, so one uncaught
            render exception on a routed page — a malformed API field a
            component didn't guard — unmounted the whole tree to a blank,
            unrecoverable white page. Scoped to the outlet rather than the app
            root so the sidebar and top bar survive a crash, keyed on the
            route so navigating away from the page that crashed clears it.
          */}
          <ErrorBoundary resetKey={location.pathname + location.search}>
            <Outlet />
          </ErrorBoundary>
        </main>
      </div>
      <Toaster />
      <NotificationStream />
      <CommandPalette />
    </div>
  )
}
