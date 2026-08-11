import { render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import { AuthProvider } from './features/auth/AuthProvider'
import { initialAuthState, useAuthStore } from './features/auth/authStore'

/**
 * Mirrors `main.tsx`, including `AuthProvider`.
 *
 * Every route below `RequireAuth` since A-030, so without it the store sits at
 * `status: 'unknown'` for ever and this file asserts against the session splash.
 * The provider is not stubbed: the mock `POST /auth/refresh` returns the seeded
 * session, so these tests exercise the real restore path rather than a fake
 * that would pass even if the guard were broken.
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

// The store is a module singleton, so a session restored in one test would
// otherwise still be signed in for the next — and a guard bug would hide behind
// the leftover state from whichever test ran first.
//
// Back to `unknown`, not `signOut()`: signing out means the session *ended*, so
// `RequireAuth` would redirect to /login before the startup refresh had a chance
// to answer, and every assertion below would run against the login screen.
beforeEach(() => useAuthStore.setState(initialAuthState))

describe('App shell', () => {
  it('renders the sidebar nav, hiding Masters for a non-admin mock user', async () => {
    renderApp()
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    for (const label of ['Dashboard', 'My Tasks', 'Tickets', 'Projects', 'Chat', 'Reports', 'Settings']) {
      expect(within(nav).getByRole('link', { name: label })).toBeInTheDocument()
    }
    // The seeded mock session is Ravi Kumar, a Developer — Masters is Admin-only.
    expect(within(nav).queryByRole('link', { name: 'Masters' })).not.toBeInTheDocument()
  })

  it('defaults to the Dashboard route', async () => {
    renderApp()
    expect(await screen.findByText('Dashboard', { selector: 'p' })).toBeInTheDocument()
  })

  it('shows the mock user in the avatar menu once /me resolves', async () => {
    renderApp()
    // Ravi Kumar → initials "RK", once the mock /me request resolves.
    //
    // The explicit timeout is not decoration. `waitFor` defaults to 1000 ms,
    // and this is the only assertion in the file that waits on a real round
    // trip through MSW — which carries a deliberate 120 ms latency so loading
    // states are visible while developing. Alone it settles in well under a
    // second; in a full run, competing with nineteen other suites for the CPU,
    // it intermittently does not, and the failure looks like "/me returned
    // nothing" rather than "the clock ran out". Every other network-waiting
    // assertion in this codebase already passes 4000.
    await waitFor(
      () => expect(screen.getByRole('button', { name: /Account menu/i })).toHaveTextContent('RK'),
      { timeout: 4000 },
    )
  })
})
