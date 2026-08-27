import { useEffect } from 'react'
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

  /*
    BUG-001 · the shell must not be able to scroll the document.

    Two scrollbars were showing side by side on every shell screen: `<main>`'s
    own, and a second, document-level one that scrolled the entire shell —
    sidebar and top bar included — up and out of the window, leaving dead
    background beneath it and the create form's action bar stranded at the
    bottom of the window with nothing above it.

    The shell being `position: fixed` (below) is what stops it *contributing*
    height to the document. This effect is what stops anything else from doing
    so: while the shell is mounted the document itself does not scroll, full
    stop, so `<main>` is the only place the app scrolls. Scoped to the shell's
    lifetime rather than written into a stylesheet because the auth screens —
    S-01 to S-03, which render outside the shell — are `min-h-screen` pages
    that must still scroll the document normally on a short window.

    The previous inline value is captured and restored rather than cleared, so
    unmounting hands the document back exactly as it was found. Radix's dialogs
    do the same thing while a modal is open and restore what they captured, so
    the two nest without either clobbering the other.
  */
  useEffect(() => {
    const { style } = document.body
    const previous = style.overflow
    style.overflow = 'hidden'
    return () => {
      style.overflow = previous
    }
  }, [])

  return (
    /*
      `fixed inset-0`, not `h-screen`. `h-screen` is 100vh measured against a
      document that anything at body level can make taller, and when that
      happened the shell rendered as a 100vh band at the top of a taller page.
      Fixed to the viewport it fills the window exactly and adds no height of
      its own, whatever else is on the page.

      `<main>`'s `overflow-y-auto` below is deliberately untouched: the inner
      scrollbar is the one the app is supposed to have.

      `overflow-clip`, not `overflow-hidden`. Both clip identically, but
      `hidden` still leaves this div a scroll container — one the browser
      itself is willing to move, with no scrollbar to show it happened.
      Checking the ticket detail page's "Client visible" radio was the
      reproduction: `<main>` (the div below, with its own `overflow-y-auto`)
      happened to have nothing left to scroll at that moment, so the browser's
      own scroll-the-checked-control-into-view walked past it to the next
      scrollable ancestor it could find — this div — and set *its* scrollTop
      instead, dragging the sidebar and top bar dozens of pixels off-screen.
      Confirmed by instrumenting `Element.prototype.scrollTop` and
      `scrollIntoView`: neither fired, so nothing in this app's own code is
      scrolling anything — it is the browser's native focus-scroll picking the
      wrong ancestor because `hidden` still qualifies as one. `overflow: clip`
      does not establish a scroll container at all, so that walk has nothing
      here to find.
    */
    <div className="fixed inset-0 flex overflow-clip bg-app">
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
