import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, expect, it } from 'vitest'

import type { PortalSession } from '@/api/generated/model/portalSession'

import { PortalRequireAuth } from './PortalRequireAuth'
import { initialPortalAuthState, usePortalAuthStore } from './portalAuthStore'

/**
 * C-121 · the portal route guard — `RequireAuth.test.tsx`'s shape, one
 * principal type over. Convenience only: `PortalPasswordChangeGate` and
 * `ClientPrincipal` are the real boundary, server-side.
 */

const session = (overrides: Partial<PortalSession> = {}): PortalSession => ({
  accessToken: 'portal.test.token',
  expiresIn: 900,
  mustChangePassword: false,
  user: { accountId: 7, displayName: 'Northwind Ops', email: 'ops@northwind.test', hasTicketing: false, hasOnboarding: true },
  ...overrides,
})

function renderAt(pathname: string) {
  return render(
    <MemoryRouter initialEntries={[pathname]}>
      <Routes>
        <Route element={<PortalRequireAuth />}>
          <Route path="/portal/set-password" element={<div>set password screen</div>} />
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

it('shows neither the screen nor the login form before the session is known', () => {
  renderAt('/portal/onboarding')

  expect(screen.queryByText('protected screen')).not.toBeInTheDocument()
  expect(screen.queryByTestId('login')).not.toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('Restoring your session')
})

it('redirects an anonymous visitor to the portal login screen, carrying where they were going', () => {
  usePortalAuthStore.getState().signOut()

  renderAt('/portal/onboarding')

  expect(screen.getByTestId('login')).toHaveTextContent('from:/portal/onboarding')
})

it('renders the screen once a session exists', () => {
  usePortalAuthStore.getState().signIn(session())

  renderAt('/portal/onboarding')

  expect(screen.getByText('protected screen')).toBeInTheDocument()
})

it('closes every route but the set-password screen while the account must change its password', () => {
  usePortalAuthStore.getState().signIn(session({ mustChangePassword: true }))

  renderAt('/portal/onboarding')

  expect(screen.getByText('set password screen')).toBeInTheDocument()
  expect(screen.queryByText('protected screen')).not.toBeInTheDocument()
})

it('does not redirect the set-password screen to itself', () => {
  usePortalAuthStore.getState().signIn(session({ mustChangePassword: true }))

  renderAt('/portal/set-password')

  expect(screen.getByText('set password screen')).toBeInTheDocument()
})
