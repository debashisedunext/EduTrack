import { beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import * as http from '@/api/http'
import * as portalApi from '@/api/generated/portal/portal'

import { PortalLoginPage } from './PortalLoginPage'
import { initialPortalAuthState, usePortalAuthStore } from './portalAuthStore'

/**
 * CP-01 · the one thing about this screen that is not obvious from reading
 * it: **the login request must go out anonymous.**
 *
 * `api/http.ts` holds a single module-level access token attached to every
 * generated call, staff or portal. It is module state, so it survives
 * client-side navigation — a staff user who opens `/portal/login` in the
 * same tab still has their bearer token in that slot. A-111's guard answers
 * a non-client principal with **404, not 401**, so the portal login route
 * reads as missing and the screen blames the client's password for a failure
 * that has nothing to do with it. Confirmed against the running API: the
 * same request is 200 with no Authorization header and 404 with a staff one.
 */

beforeEach(() => {
  usePortalAuthStore.setState(initialPortalAuthState)
  vi.restoreAllMocks()
})

function renderLogin() {
  render(
    <MemoryRouter initialEntries={['/portal/login']}>
      <PortalLoginPage />
    </MemoryRouter>,
  )
}

it('clears the shared access token before signing in, so the POST goes out anonymous', async () => {
  // A staff session's token, left in the shared slot by the tab's previous life.
  http.setAccessToken('staff.token.from.this.tab')

  let tokenDuringLogin: string | null | undefined
  vi.spyOn(portalApi, 'portalLogin').mockImplementation(async () => {
    tokenDuringLogin = http.getAccessToken()
    return {
      data: {
        accessToken: 'portal.token',
        expiresIn: 900,
        mustChangePassword: false,
        client: {
          username: 'KVVARANASI.shivendra',
          displayName: 'Shivendra Keshari',
          hasTicketing: false,
          hasOnboarding: true,
        },
      },
    }
  })

  renderLogin()
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'KVVARANASI.shivendra' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'ClientPortal#2026' } })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

  await waitFor(() => expect(portalApi.portalLogin).toHaveBeenCalled())

  // The assertion that matters: no inherited token was on the wire.
  expect(tokenDuringLogin).toBeNull()
  await waitFor(() => expect(usePortalAuthStore.getState().status).toBe('authenticated'))
  expect(http.getAccessToken()).toBe('portal.token')
})

/**
 * The other thing that is not obvious: **a login can succeed and still not
 * be a way in.** An account issued a temporary password answers 200 with
 * `mustChangePassword`, and the token it carries is refused by
 * `PortalPasswordChangeGate` on every portal route but the change itself. So
 * the screen must route to the form rather than to the chooser — otherwise
 * the client lands on a page whose first call 403s.
 */
it('routes a must-change login to the change-password form, not the chooser', async () => {
  vi.spyOn(portalApi, 'portalLogin').mockResolvedValue({
    data: {
      accessToken: 'portal.temporary.token',
      expiresIn: 900,
      mustChangePassword: true,
      client: {
        username: 'DEMO-101',
        displayName: 'Demo School',
        hasTicketing: false,
        hasOnboarding: true,
      },
    },
  })

  render(
    <MemoryRouter initialEntries={['/portal/login']}>
      <Routes>
        <Route path="/portal/login" element={<PortalLoginPage />} />
        <Route path="/portal/change-password" element={<div>change password screen</div>} />
        <Route path="/portal/choose" element={<div>module chooser</div>} />
      </Routes>
    </MemoryRouter>,
  )

  fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'DEMO-101' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'Demo-Passw0rd!' } })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

  expect(await screen.findByText('change password screen')).toBeInTheDocument()
  expect(screen.queryByText('module chooser')).not.toBeInTheDocument()
  expect(usePortalAuthStore.getState().mustChangePassword).toBe(true)
})
