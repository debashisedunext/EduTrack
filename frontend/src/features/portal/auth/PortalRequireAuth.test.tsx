import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, expect, it } from 'vitest'

import type { PortalLoginResult } from '@/api/generated/model/portalLoginResult'

import { PortalRequireAuth } from './PortalRequireAuth'
import { initialPortalAuthState, usePortalAuthStore } from './portalAuthStore'

/**
 * A-130 · the portal route guard — `RequireAuth.test.tsx`'s shape, one
 * principal type over. Convenience only: `ClientPrincipal` is the real
 * boundary, server-side.
 */

const loginResult = (overrides: Partial<PortalLoginResult> = {}): PortalLoginResult => ({
  accessToken: 'portal.test.token',
  expiresIn: 900,
  mustChangePassword: false,
  client: { username: 'northwind.ops', displayName: 'Northwind Ops', hasTicketing: false, hasOnboarding: true },
  ...overrides,
})

function renderAt(pathname: string) {
  return render(
    <MemoryRouter initialEntries={[pathname]}>
      <Routes>
        <Route element={<PortalRequireAuth />}>
          <Route path="/portal/onboarding" element={<div>protected screen</div>} />
        </Route>
        <Route path="/portal/login" element={<WhereFrom />} />
      </Routes>
    </MemoryRouter>,
  )
}

function WhereFrom() {
  const location = useLocation()
  const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname
  return <div data-testid="login">from:{from ?? 'none'}</div>
}

beforeEach(() => usePortalAuthStore.setState(initialPortalAuthState))

it('redirects an anonymous visitor to the portal login screen, carrying where they were going', () => {
  renderAt('/portal/onboarding')

  expect(screen.getByTestId('login')).toHaveTextContent('from:/portal/onboarding')
})

it('renders the screen once a session exists', () => {
  usePortalAuthStore.getState().signIn(loginResult())

  renderAt('/portal/onboarding')

  expect(screen.getByText('protected screen')).toBeInTheDocument()
})

/**
 * The forced-change branch. Enforced here rather than per-page for
 * `RequireAuth`'s reason: a check on the chooser alone leaves
 * `/portal/onboarding` open to anyone who types the URL, and the server-side
 * gate would then be the only thing stopping them — which shows up as an
 * unexplained error page instead of a form.
 */
it('redirects a client who still owes a password change to the change form', () => {
  usePortalAuthStore.getState().signIn(loginResult({ mustChangePassword: true }))

  render(
    <MemoryRouter initialEntries={['/portal/onboarding']}>
      <Routes>
        <Route element={<PortalRequireAuth />}>
          <Route path="/portal/onboarding" element={<div>protected screen</div>} />
          <Route path="/portal/change-password" element={<div>change password screen</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )

  expect(screen.getByText('change password screen')).toBeInTheDocument()
  expect(screen.queryByText('protected screen')).not.toBeInTheDocument()
})

it('does not redirect away from the change form itself, which would be a loop', () => {
  usePortalAuthStore.getState().signIn(loginResult({ mustChangePassword: true }))

  render(
    <MemoryRouter initialEntries={['/portal/change-password']}>
      <Routes>
        <Route element={<PortalRequireAuth />}>
          <Route path="/portal/change-password" element={<div>change password screen</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )

  expect(screen.getByText('change password screen')).toBeInTheDocument()
})

it('lets a client who has chosen their own password through', () => {
  usePortalAuthStore.getState().signIn(loginResult({ mustChangePassword: false }))

  renderAt('/portal/onboarding')

  expect(screen.getByText('protected screen')).toBeInTheDocument()
})
