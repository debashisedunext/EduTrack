import { beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import * as http from '@/api/http'
import * as portalApi from '@/api/generated/portal/portal'

import { PortalChangePasswordPage } from './PortalChangePasswordPage'
import { initialPortalAuthState, usePortalAuthStore } from './portalAuthStore'

/**
 * The forced password change, from the client's side.
 *
 * The screen itself is not the control — `PortalPasswordChangeGate` refuses
 * every other portal route server-side. What these cover is the part that is
 * only true if this component gets it right: that the successor session
 * replaces the one that could not be used, and that the form does not send a
 * request the server is certain to refuse.
 */

const settledSession = {
  data: {
    accessToken: 'portal.settled.token',
    expiresIn: 900,
    mustChangePassword: false,
    client: {
      username: 'DEMO-101',
      displayName: 'Demo School',
      hasTicketing: false,
      hasOnboarding: true,
    },
  },
}

beforeEach(() => {
  usePortalAuthStore.setState({
    ...initialPortalAuthState,
    status: 'authenticated',
    mustChangePassword: true,
    client: settledSession.data.client,
    expiresAt: Date.now() + 900_000,
  })
  sessionStorage.clear()
  vi.restoreAllMocks()
})

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/portal/change-password']}>
      <Routes>
        <Route path="/portal/change-password" element={<PortalChangePasswordPage />} />
        <Route path="/portal/choose" element={<div>module chooser</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

function fillIn({ current, next }: { current: string; next: string }) {
  fireEvent.change(screen.getByLabelText('Temporary password'), { target: { value: current } })
  fireEvent.change(screen.getByLabelText('New password'), { target: { value: next } })
  fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: next } })
}

/**
 * The assertion that matters most. The token in hand still carries the
 * must-change claim, and the portal has no refresh route — so if this page
 * does not adopt the session the server hands back, the client changes their
 * password successfully and is then refused by the gate holding the only
 * token they have.
 */
it('adopts the session the change returns, so the client is not left on a refused token', async () => {
  vi.spyOn(portalApi, 'changePortalPassword').mockResolvedValue(settledSession)
  renderPage()

  fillIn({ current: 'Ed-abc23xyz9kp4', next: 'Monsoon-River-42!' })
  fireEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

  expect(await screen.findByText('module chooser')).toBeInTheDocument()
  await waitFor(() => expect(usePortalAuthStore.getState().mustChangePassword).toBe(false))
  expect(http.getAccessToken()).toBe('portal.settled.token')
})

/**
 * The server refuses this with `password-unchanged` whatever else is true of
 * the password, because a forced change that accepted the temporary password
 * back would clear the flag while leaving the staff-readable credential in
 * place. Caught here too so the client is told without a round trip.
 */
it('refuses the temporary password as its own replacement, without asking the server', () => {
  const change = vi.spyOn(portalApi, 'changePortalPassword')
  renderPage()

  fillIn({ current: 'Demo-Passw0rd!', next: 'Demo-Passw0rd!' })
  fireEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

  expect(change).not.toHaveBeenCalled()
})

it('refuses a confirmation that does not match', async () => {
  const change = vi.spyOn(portalApi, 'changePortalPassword')
  renderPage()

  fireEvent.change(screen.getByLabelText('Temporary password'), {
    target: { value: 'Ed-abc23xyz9kp4' },
  })
  fireEvent.change(screen.getByLabelText('New password'), {
    target: { value: 'Monsoon-River-42!' },
  })
  fireEvent.change(screen.getByLabelText('Confirm new password'), {
    target: { value: 'Monsoon-River-43!' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

  expect(await screen.findByText('Both passwords must match.')).toBeInTheDocument()
  expect(change).not.toHaveBeenCalled()
})

/**
 * Mirrors `PortalPasswordRules`, which is the authority. This only saves the
 * client a refusal they could have been told about locally.
 */
it('refuses a password the portal itself would refuse', async () => {
  const change = vi.spyOn(portalApi, 'changePortalPassword')
  renderPage()

  fillIn({ current: 'Ed-abc23xyz9kp4', next: 'alllowercaseletters' })
  fireEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

  expect(await screen.findByText(/upper and lower case/i)).toBeInTheDocument()
  expect(change).not.toHaveBeenCalled()
})

/**
 * There must be no way past this screen that is not a new password. Offering
 * one would leave a credential an operator read aloud on a live account for
 * as long as the client kept dismissing it.
 */
it('offers no way to skip', () => {
  renderPage()

  expect(screen.queryByRole('button', { name: /skip|later|not now/i })).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: /skip|later|not now/i })).not.toBeInTheDocument()
})
