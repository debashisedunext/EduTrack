import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { ObClientAccountPanel } from './ObClientAccountPanel'

/**
 * B-126 · the client-account panel against the mock server.
 *
 * Fixture note — `db.ts`'s `obClientAccounts`: only Contoso (client 3) has a
 * portal login. Northwind (1) and Acme (2) have none, which is what makes the
 * create path reachable rather than always answering 409. Acme's contacts
 * include an active primary, so the 422 case needs a client without one and is
 * covered server-side in `ClientAccountAdminServiceTest` instead — the mock's
 * three clients all have a primary, and inventing a fourth here would be
 * testing a fixture rather than the panel.
 */
function renderPanel(obClientId: number) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ObClientAccountPanel obClientId={obClientId} />
    </QueryClientProvider>,
  )
}

describe('OB-05 client-account panel', () => {
  it('offers to create a login for a client that has none', async () => {
    renderPanel(1)

    expect(await screen.findByRole('button', { name: /create portal login/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /email a new link/i })).not.toBeInTheDocument()
  })

  it('shows the login for a client that has one, and never a credential', async () => {
    renderPanel(3)

    expect(await screen.findByText('CONTOSO.arjun')).toBeInTheDocument()
    // The response carries no password, hash or link, so there is nothing on
    // the panel to copy. This is the assertion that fails if somebody ever
    // "helpfully" adds one to the server.
    expect(screen.queryByText(/\$argon2id/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/set-password\?token=/i)).not.toBeInTheDocument()
  })

  it('creating one reports that a link was emailed, not that a password was set', async () => {
    const user = userEvent.setup()
    renderPanel(2)

    await user.click(await screen.findByRole('button', { name: /create portal login/i }))

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent(/emailed to the primary spoc/i)
    expect(status).not.toHaveTextContent(/password is/i)
  })

  it('says "Never" rather than nothing when the client has not signed in', async () => {
    const user = userEvent.setup()
    renderPanel(2)

    await user.click(await screen.findByRole('button', { name: /create portal login/i }))

    // An empty cell reads as missing data; "Never" is the answer support is
    // actually looking for.
    expect(await screen.findByText('Never')).toBeInTheDocument()
  })

  it('reissuing says the earlier link has stopped working', async () => {
    const user = userEvent.setup()
    renderPanel(3)

    await user.click(await screen.findByRole('button', { name: /email a new link/i }))

    expect(await screen.findByRole('status'))
      .toHaveTextContent(/any earlier link no longer works/i)
  })

  it('disabling flips the control rather than leaving a dead button', async () => {
    const user = userEvent.setup()
    renderPanel(3)

    await user.click(await screen.findByRole('button', { name: /disable login/i }))

    expect(await screen.findByRole('button', { name: /enable login/i })).toBeInTheDocument()
    expect(await screen.findByText('Disabled')).toBeInTheDocument()
  })

  it('warns that disabling also kills a link already sent', async () => {
    renderPanel(3)

    // The operator pressing "disable" believes they have closed the door. This
    // line is what makes that true on screen as well as on the server.
    expect(await screen.findByText(/invalidates any link already emailed/i)).toBeInTheDocument()
  })
})
