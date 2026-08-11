import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import { AuthProvider } from './features/auth/AuthProvider'
import { initialAuthState, useAuthStore } from './features/auth/authStore'

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

beforeEach(() => useAuthStore.setState(initialAuthState))

it('navigates from the dashboard to the ticket list', async () => {
  const user = userEvent.setup()
  renderApp()
  await screen.findByText('Dashboard', { selector: 'p' })

  const nav = await screen.findByRole('navigation', { name: 'Main' })
  const link = await waitFor(() => nav.querySelector('a[href="/tickets"]')!)
  await user.click(link)

  await waitFor(() => console.log('URL NOW:', window.location.pathname), { timeout: 4000 })
  await new Promise((r) => setTimeout(r, 1500))
  console.log('BODY:', document.body.innerHTML.slice(0, 3000))
})
