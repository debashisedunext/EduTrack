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
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Account menu/i })).toHaveTextContent('RK'),
    )
  })
})
