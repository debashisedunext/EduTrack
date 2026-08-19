import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { Me } from '@/api/generated/model'
import { initialAuthState, useAuthStore } from '@/features/auth/authStore'

import { Sidebar } from './Sidebar'

/**
 * The Admin-only navigation, which was broken in the quietest possible way.
 *
 * <p>`Sidebar` asked `GET /api/v1/me` for the caller's role. That endpoint is
 * declared in the contract and **has never been implemented**, so the request
 * 404s, `isAdmin` was false for everybody, and Masters — later joined by
 * A-071's Audit log — was hidden from Admins too.
 *
 * <p><b>`App.test.tsx` covered this and passed throughout.</b> Its assertion is
 * that a Developer does *not* see Masters, and that was true: nobody saw
 * Masters. A negative assertion alone cannot tell "correctly hidden from this
 * role" from "hidden from everyone because the check is broken", and the
 * failure is invisible in exactly the direction a reviewer skims past. So this
 * file asserts the positive case, which is the one that was wrong.
 *
 * <p>Rendered directly rather than through `App`, because `AuthProvider`
 * restores the seeded mock session on mount and would overwrite the role under
 * test. The unit whose logic changed is this component.
 */

const ADMIN: Me = { id: 1, displayName: 'Priya Nair', role: 'ADMIN' }
const DEVELOPER: Me = { id: 4, displayName: 'Ravi Kumar', role: 'DEVELOPER' }

/** The store is a module singleton; a role left behind would decide the next test. */
beforeEach(() => useAuthStore.setState(initialAuthState))

function renderSidebarAs(user: Me | null) {
  useAuthStore.setState({ status: user ? 'authenticated' : 'anonymous', user })
  return render(
    <MemoryRouter>
      <Sidebar />
    </MemoryRouter>,
  )
}

function nav() {
  return screen.getByRole('navigation', { name: 'Main' })
}

describe('Sidebar', () => {
  it('shows the Admin-only entries to an Admin', () => {
    renderSidebarAs(ADMIN)

    expect(within(nav()).getByRole('link', { name: 'Masters' })).toBeInTheDocument()
    expect(within(nav()).getByRole('link', { name: 'Audit log' })).toBeInTheDocument()
  })

  it('hides them from every other role', () => {
    renderSidebarAs(DEVELOPER)

    expect(within(nav()).queryByRole('link', { name: 'Masters' })).not.toBeInTheDocument()
    expect(within(nav()).queryByRole('link', { name: 'Audit log' })).not.toBeInTheDocument()
  })

  /**
   * Not reachable through the shell — `RequireAuth` redirects first — but the
   * component must not decide "Admin" from an absent user, because that is the
   * direction that leaks a screen rather than withholding one.
   */
  it('hides them when there is no session at all', () => {
    renderSidebarAs(null)

    expect(within(nav()).queryByRole('link', { name: 'Masters' })).not.toBeInTheDocument()
  })

  it('shows the entries every role gets, whoever is signed in', () => {
    renderSidebarAs(DEVELOPER)

    for (const label of ['Dashboard', 'My Tasks', 'Tickets', 'Projects', 'Chat', 'Reports', 'Settings']) {
      expect(within(nav()).getByRole('link', { name: label })).toBeInTheDocument()
    }
  })

  /**
   * The regression guard. The role must come from the session already in
   * memory: a component that fetches it again depends on an endpoint nobody has
   * built, and fails open to "not an Admin" when the request errors — which is
   * precisely how this broke. Rendering with no query client at all is what
   * makes that structural: a `useQuery` here would throw rather than quietly
   * return undefined.
   */
  it('reads the role without issuing a request', () => {
    expect(() => renderSidebarAs(ADMIN)).not.toThrow()
    expect(within(nav()).getByRole('link', { name: 'Audit log' })).toBeInTheDocument()
  })
})
