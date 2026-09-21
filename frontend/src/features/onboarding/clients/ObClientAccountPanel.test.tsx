import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { HttpResponse, http } from 'msw'

import { server } from '@/mocks/server'

import { ObClientAccountPanel } from './ObClientAccountPanel'

/**
 * B-126 · the client-account panel against the mock server.
 *
 * Fixture note — `db.ts`'s `obClientAccounts`: only GreenValley (client 1) has
 * a portal login. The other seven clients — Sunrise (2) is the one used here —
 * have none, which is what makes the create path reachable rather than always
 * answering 409. Sunrise's contacts include an active primary (Arjun Shetty),
 * so the 422 case needs a client without one and is covered server-side in
 * `ClientAccountAdminServiceTest` instead — the mock's eight clients all have
 * a primary, and inventing a ninth here would be testing a fixture rather
 * than the panel.
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
    renderPanel(2)

    expect(await screen.findByRole('button', { name: /create portal login/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /email a new link/i })).not.toBeInTheDocument()
  })

  it('shows the login for a client that has one, and never a credential', async () => {
    renderPanel(1)

    expect(await screen.findByText('GREENVALLEY.deepa')).toBeInTheDocument()
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
    renderPanel(1)

    await user.click(await screen.findByRole('button', { name: /email a new link/i }))

    expect(await screen.findByRole('status'))
      .toHaveTextContent(/any earlier link no longer works/i)
  })

  it('disabling flips the control rather than leaving a dead button', async () => {
    const user = userEvent.setup()
    renderPanel(1)

    await user.click(await screen.findByRole('button', { name: /disable login/i }))

    expect(await screen.findByRole('button', { name: /enable login/i })).toBeInTheDocument()
    expect(await screen.findByText('Disabled')).toBeInTheDocument()
  })

  it('warns that disabling also kills a link already sent', async () => {
    renderPanel(1)

    // The operator pressing "disable" believes they have closed the door. This
    // line is what makes that true on screen as well as on the server.
    expect(await screen.findByText(/invalidates any link already emailed/i)).toBeInTheDocument()
  })

  /**
   * The temporary password, which is how a portal login is issued.
   *
   * The mock has no switch for `edutrack.portal.temporary-password.enabled`
   * and should not grow one: it would be a second implementation of a server
   * decision, and the panel's job is only to render whichever answer arrives.
   * So these override the response directly.
   */
  describe('when a temporary password comes back', () => {
    function respondWithTemporaryPassword(temporaryPassword: string) {
      server.use(
        http.post('*/onboarding/clients/:obClientId/account', () =>
          HttpResponse.json({
            data: {
              id: 99,
              username: 'SUNRISEEDTEC.arjun',
              displayName: 'Arjun Shetty',
              email: 'arjun@sunrise.example',
              isActive: true,
              // Still TRUE, and that is the whole mechanism. The password
              // below buys one session which can do nothing but replace it;
              // clearing the flag here would leave a staff-readable credential
              // on a live account indefinitely.
              mustChangePassword: true,
              lastLoginAt: null,
              lockedUntil: null,
              credentialSentAt: '2026-09-10T04:00:00Z',
              temporaryPassword,
            },
          }),
        ),
      )
    }

    it('shows the username and password together, as one hand-over', async () => {
      const user = userEvent.setup()
      respondWithTemporaryPassword('Ed-abc23xyz9kp4')
      renderPanel(2)

      await user.click(await screen.findByRole('button', { name: /create portal login/i }))

      expect(await screen.findByText('Ed-abc23xyz9kp4')).toBeInTheDocument()
      // Both halves, or it is not something anybody can sign in with.
      expect(screen.getAllByText('SUNRISEEDTEC.arjun').length).toBeGreaterThan(0)
    })

    /**
     * The copy has to say the password is temporary. An operator who reads it
     * as the client's permanent password files it somewhere, and the bargain
     * that makes showing a credential acceptable — that it survives one
     * sign-in — stops holding the moment somebody believes otherwise.
     */
    it('says the client will be asked to choose their own', async () => {
      const user = userEvent.setup()
      respondWithTemporaryPassword('Ed-abc23xyz9kp4')
      renderPanel(2)

      await user.click(await screen.findByRole('button', { name: /create portal login/i }))

      expect(await screen.findByText(/choose their own password/i)).toBeInTheDocument()
      expect(screen.getByText(/temporary password/i)).toBeInTheDocument()
    })

    /*
      The guard that matters. `temporaryPassword` is absent on every read of
      the account and on any deployment that has switched the flow off, and the
      panel must then render no credential at all — this fails if the field is
      ever defaulted to a value rather than left null.
    */
    it('renders no credential when the field is absent', async () => {
      const user = userEvent.setup()
      renderPanel(2)

      await user.click(await screen.findByRole('button', { name: /create portal login/i }))

      await screen.findByRole('status')
      expect(screen.queryByText(/send these to the client/i)).not.toBeInTheDocument()
      expect(screen.queryByText(/^Ed-/)).not.toBeInTheDocument()
    })
  })
})
