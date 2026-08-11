import { render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'

function renderApp() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>,
  )
}

describe('App shell', () => {
  it('renders the sidebar nav, hiding Masters for a non-admin mock user', async () => {
    renderApp()
    const nav = screen.getByRole('navigation', { name: 'Main' })
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
