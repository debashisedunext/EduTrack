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
