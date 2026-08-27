import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from '../App'
import { AuthProvider } from '../features/auth/AuthProvider'
import { initialAuthState, useAuthStore } from '../features/auth/authStore'

/**
 * BUG-001 · the shell must be the only thing that scrolls, and only inside
 * `<main>`.
 *
 * <p>The bug was two scrollbars on top of each other: `<main>`'s, which is the
 * one the app is meant to have, and a second document-level one that scrolled
 * the whole shell — sidebar, top bar and all — up out of the window, leaving
 * dead background below it. `h-screen` is 100vh of a document that anything at
 * body level can make taller, so the shell rendered as a band at the top of a
 * taller page instead of filling it.
 *
 * <p>jsdom has no layout engine, so no test can observe a scrollbar. What it
 * *can* observe is the two things that decide whether one appears: that the
 * shell takes itself out of document flow, and that it holds the document's
 * own scrolling shut for as long as it is mounted. Both are asserted here, and
 * so is the inner scroll — because "no scrollbars at all" would satisfy the
 * first two and is not the fix.
 *
 * <p>Rendered through `App` rather than `AppShell` directly, mirroring
 * `App.test.tsx`: every shell route sits below `RequireAuth`, and the mock
 * `POST /auth/refresh` is what gets the store out of `status: 'unknown'`.
 */
function renderApp() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </QueryClientProvider>,
  )
}

// The auth store is a module singleton — see App.test.tsx for why this is
// `initialAuthState` and not `signOut()`.
beforeEach(() => useAuthStore.setState(initialAuthState))

describe('AppShell scrolling', () => {
  it('takes the shell out of document flow, leaving <main> the one place it scrolls', async () => {
    renderApp()
    const main = await screen.findByRole('main', {}, { timeout: 4000 })
    const shell = main.closest('div.fixed')

    expect(shell).not.toBeNull()
    // `fixed inset-0` — pinned to the viewport, contributing nothing to the
    // document's height whatever else is on the page.
    expect(shell).toHaveClass('fixed', 'inset-0')
    // `h-screen` is what made it a 100vh band inside a taller document.
    expect(shell).not.toHaveClass('h-screen')
    // The inner scrollbar is the one that is supposed to be there. A fix that
    // removed this would look like a pass on the two assertions above.
    expect(main).toHaveClass('overflow-y-auto')
  })

  /**
   * A second bug in the same family, found on the ticket detail page: `hidden`
   * clips identically to `clip` but — unlike `clip` — still makes this div a
   * scroll container, and the browser's own scroll-a-just-checked-control-
   * into-view will walk up to it and set its scrollTop when `<main>` has
   * nothing left to scroll, dragging the sidebar and top bar off-screen with
   * no scrollbar to explain it. jsdom has no layout engine and cannot
   * reproduce the scroll itself; this pins the one class jsdom *can* see so
   * `hidden` does not quietly come back.
   */
  it('clips the shell rather than hiding it, so the browser cannot scroll it either', async () => {
    renderApp()
    const main = await screen.findByRole('main', {}, { timeout: 4000 })
    const shell = main.closest('div.fixed')

    expect(shell).not.toHaveClass('overflow-hidden')
    expect(shell).toHaveClass('overflow-clip')
  })

  it('holds the document itself shut while the shell is mounted', async () => {
    const { unmount } = renderApp()
    await screen.findByRole('main', {}, { timeout: 4000 })

    expect(document.body.style.overflow).toBe('hidden')

    // Restored on unmount, not cleared: the auth screens render outside the
    // shell and are `min-h-screen` pages that must still scroll the document.
    unmount()
    expect(document.body.style.overflow).toBe('')
  })
})
