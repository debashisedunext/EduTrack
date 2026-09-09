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

/** Holds both modules, like the only dual-module account in the fixtures. */
const OB_ADMIN = { ...ADMIN, modules: ['TICKETING', 'ONBOARDING'] } as Me
/** Entitled to ticketing alone — the case the module check exists for. */
const TICKETING_ONLY = { ...DEVELOPER, modules: ['TICKETING'] } as Me

/** The store is a module singleton; a role left behind would decide the next test. */
beforeEach(() => useAuthStore.setState(initialAuthState))

function renderSidebarAs(user: Me | null, route = '/') {
  useAuthStore.setState({ status: user ? 'authenticated' : 'anonymous', user })
  return render(
    <MemoryRouter initialEntries={[route]}>
      <Sidebar />
    </MemoryRouter>,
  )
}

function nav() {
  return screen.getByRole('navigation', { name: 'Main' })
}

function obNav() {
  return screen.getByRole('navigation', { name: 'Onboarding' })
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

/**
 * A-129 · the onboarding module's navigation.
 *
 * <p>Twelve onboarding screens were merged and reachable only by typing their
 * URL, because this file had no concept of a second module. The assertions that
 * matter are the two swaps — ticketing nav gives way to onboarding nav on an
 * `/onboarding/**` route and comes back off it — and the entitlement check,
 * which is the one that could leak a module's shape to somebody the server
 * would 404.
 */
describe('Sidebar · onboarding module', () => {
  it('swaps to the onboarding navigation on an onboarding route', () => {
    renderSidebarAs(OB_ADMIN, '/onboarding/dashboard')

    for (const label of ['Dashboard', 'Clients', 'New client', 'Reports', 'TAT & escalation']) {
      expect(within(obNav()).getByRole('link', { name: label })).toBeInTheDocument()
    }
    // The ticketing entries are gone, not merely pushed down.
    expect(within(obNav()).queryByRole('link', { name: 'Tickets' })).not.toBeInTheDocument()
    expect(screen.getByText('Client Onboarding')).toBeInTheDocument()
  })

  it('keeps the ticketing navigation everywhere else', () => {
    renderSidebarAs(OB_ADMIN, '/dashboard')

    expect(within(nav()).getByRole('link', { name: 'Tickets' })).toBeInTheDocument()
    expect(screen.queryByText('Client Onboarding')).not.toBeInTheDocument()
  })

  /**
   * The path alone must not be enough. A caller without the grant gets 404s
   * from `ObModuleGuard` for every byte of data on these screens, so drawing
   * the module's navigation for them would be the frontend describing a module
   * the server denies exists.
   */
  it('does not draw the onboarding navigation without the module grant', () => {
    renderSidebarAs(TICKETING_ONLY, '/onboarding/dashboard')

    expect(screen.queryByRole('navigation', { name: 'Onboarding' })).not.toBeInTheDocument()
    expect(within(nav()).getByRole('link', { name: 'Tickets' })).toBeInTheDocument()
  })

  it('does not draw it for a session with no modules at all', () => {
    renderSidebarAs(ADMIN, '/onboarding/dashboard')

    expect(screen.queryByRole('navigation', { name: 'Onboarding' })).not.toBeInTheDocument()
  })

  /**
   * `NavLink` matches on prefix, so `/onboarding/clients` would light up on the
   * wizard route too and the rail would show two current pages at once.
   */
  it('marks exactly one row current when the wizard sits under the list', () => {
    renderSidebarAs(OB_ADMIN, '/onboarding/clients/new')

    expect(within(obNav()).getByRole('link', { name: 'New client' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(obNav()).getByRole('link', { name: 'Clients' })).not.toHaveAttribute(
      'aria-current',
    )
  })

  it('keeps Clients current on a client detail page', () => {
    renderSidebarAs(OB_ADMIN, '/onboarding/clients/42')

    expect(within(obNav()).getByRole('link', { name: 'Clients' })).toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  /**
   * Every row must lead somewhere that exists. Prerequisites master was the
   * one entry withheld while no task had built its screen; now that the
   * screen and its route are real, the row must be offered — a master
   * reachable only by typing its URL reads as a missing product.
   */
  it('offers Prerequisites master now that its screen is built', () => {
    renderSidebarAs(OB_ADMIN, '/onboarding/dashboard')

    expect(within(obNav()).getByRole('link', { name: 'Prerequisites master' }))
      .toHaveAttribute('href', '/onboarding/prereq-master')
  })

  // A-117 landed its screen, so the row it was waiting on is now real.
  it('offers Roles & module access now that A-117 has a screen', () => {
    renderSidebarAs(OB_ADMIN, '/onboarding/dashboard')

    expect(within(obNav()).getByRole('link', { name: 'Roles & module access' })).toBeInTheDocument()
  })

  it('offers Module Service now that C-123 built its list page', () => {
    renderSidebarAs(OB_ADMIN, '/onboarding/dashboard')

    expect(within(obNav()).getByRole('link', { name: 'Module Service' }))
        .toHaveAttribute('href', '/onboarding/journey-templates')
  })
})
