import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { Me } from '@/api/generated/model'
import { initialAuthState, useAuthStore } from '@/features/auth/authStore'
import { useSidebarStore } from './sidebarStore'

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
/**
 * Holds the onboarding module without being a platform Admin — the majority
 * case, and the one the Administration gating divides on. Every other
 * onboarding fixture here is an Admin, so before this existed the hidden half
 * of that division was untestable.
 */
const OB_MEMBER = { ...DEVELOPER, modules: ['TICKETING', 'ONBOARDING'] } as Me
/**
 * An implementor — the module role that owns onboarding tasks, and the only
 * one My Tasks is drawn for. `OB_MEMBER` above holds the module and no role,
 * which is the session shape that existed before `moduleRoles` was exposed and
 * is still what a token issued minutes earlier carries.
 */
const OB_IMPLEMENTOR = {
  ...DEVELOPER,
  modules: ['TICKETING', 'ONBOARDING'],
  moduleRoles: { ONBOARDING: 'OB_STEP_OWNER' },
} as Me

/** The stores are module singletons; a role or a rail state left behind would decide the next test. */
beforeEach(() => {
  useAuthStore.setState(initialAuthState)
  useSidebarStore.setState({ collapsed: true })
})

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

    for (const label of ['Dashboard', 'Projects', 'Clients', 'Reports', 'TAT & escalation']) {
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
   * Module Service leads Administration.
   *
   * <p>Order is the only thing a nav array expresses, and nothing else in this
   * file asserts any of it — so the section's arrangement was a property no
   * test held, and a merge that reordered the array would have changed the
   * screen silently. Pinned as a relative position rather than an index, so
   * adding a tenth entry below does not fail this.
   */
  it('puts Module Service at the top of Administration', () => {
    renderSidebarAs(OB_ADMIN, '/onboarding/dashboard')

    const adminLinks = ['Module Service', 'Products', 'Prerequisites master',
      'Roles & module access', 'TAT & escalation', 'Notification templates']
      .map((label) => within(obNav()).getByRole('link', { name: label }))

    const positions = adminLinks.map((link) => adminLinks[0].compareDocumentPosition(link))
    // Every other Administration entry follows the first one in the document.
    expect(positions.slice(1).every((mask) => (mask & Node.DOCUMENT_POSITION_FOLLOWING) !== 0))
      .toBe(true)
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
   * Prefix matching alone lights two rows at once wherever one destination
   * sits under another, which is the whole reason `isActive` overrides exist.
   * The pair that forced it was Clients and the old four-step wizard at
   * `/onboarding/clients/new`; that wizard is gone, but the override it left
   * behind still guards a live path — a project's ribbon, reached from a mail
   * link at `/onboarding/clients/:id/products/:id`, which must name Projects
   * rather than the Clients master it happens to sit under.
   */
  it('does not light Clients on a project surface under a client path', () => {
    renderSidebarAs(OB_ADMIN, '/onboarding/clients/42/products/7')

    expect(within(obNav()).getByRole('link', { name: 'Clients' })).not.toHaveAttribute(
      'aria-current',
    )
  })

  it('keeps Projects current on the New project form', () => {
    renderSidebarAs(OB_ADMIN, '/onboarding/projects/new')

    expect(within(obNav()).getByRole('link', { name: 'Projects' })).toHaveAttribute(
      'aria-current',
      'page',
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

  // OB-15 · the tenth Administration row, on the same rule as the nine above:
  // the screen exists, so the row does.
  it('offers Implementation steps', () => {
    renderSidebarAs(OB_ADMIN, '/onboarding/dashboard')

    expect(within(obNav()).getByRole('link', { name: 'Implementation steps' }))
        .toHaveAttribute('href', '/onboarding/implementation-stages')
  })

  /**
   * The row belongs to the Onboarding module, not to ticketing. Asserted
   * because the two navigations are separate lists in one file, and an entry
   * added to the wrong constant renders perfectly — on the wrong screen.
   */
  it('does not offer Implementation steps in the ticketing rail', () => {
    renderSidebarAs(OB_ADMIN, '/dashboard')

    expect(screen.queryByRole('link', { name: 'Implementation steps' })).not.toBeInTheDocument()
  })
})

/**
 * Who sees the Onboarding module's configuration.
 *
 * <p>A non-Admin gets the three rows the module is *worked* in — Dashboard,
 * Projects, Reports — and none of the seven it is *configured* from, nor the
 * Clients master, nor the heading over them.
 *
 * <p>Both directions are asserted, and the positive one is the point. A suite
 * that only checks a Developer cannot see Module Service passes just as
 * happily when the filter hides it from everybody, which is exactly how the
 * platform `adminOnly` check stayed broken for months (see the header of this
 * file). So every case below has an Admin counterpart.
 */
describe('Sidebar · onboarding administration is Admin-only', () => {
  const ADMIN_LABELS = [
    'Clients',
    'Module Service',
    'Products',
    'Prerequisites master',
    'Implementation steps',
    'Roles & module access',
    'TAT & escalation',
    'Notification templates',
  ]

  it('gives a non-Admin exactly the four rows the module is worked in', () => {
    renderSidebarAs(OB_MEMBER, '/onboarding/dashboard')

    expect(within(obNav()).getAllByRole('link').map((a) => a.textContent))
      .toEqual(['Dashboard', 'My Tasks', 'Projects', 'Reports'])
  })

  /**
   * My Tasks is the implementor's screen and is drawn for everybody holding
   * the module all the same.
   *
   * <p>It was gated on `OB_STEP_OWNER` first. The gate came off because a
   * moderator can be assigned a task — an OB Admin or Manager who owns one had
   * no way to reach their own queue — and because dropping it is safe: the
   * endpoint answers for the caller and has no parameter to say otherwise, so
   * somebody who owns nothing opens an empty queue rather than a colleague's.
   *
   * <p>Asserted across three role shapes, because "shown to everybody" is the
   * claim, and a suite that checked one of them would pass just as happily if
   * the row had quietly become conditional again.
   */
  it.each([
    ['an implementor', () => OB_IMPLEMENTOR],
    ['somebody who owns no onboarding task', () => OB_MEMBER],
    ['an Admin', () => OB_ADMIN],
  ])('offers My Tasks to %s', (_who, user) => {
    renderSidebarAs(user(), '/onboarding/dashboard')

    expect(within(obNav()).getByRole('link', { name: 'My Tasks' }))
      .toHaveAttribute('href', '/onboarding/my-tasks')
  })

  /** The ticketing rail has its own My Tasks; this route belongs to neither by accident. */
  it('does not offer the onboarding queue in the ticketing rail', () => {
    renderSidebarAs(OB_MEMBER, '/dashboard')

    expect(screen.queryByRole('link', { name: 'My Tasks' }))
      .not.toHaveAttribute('href', '/onboarding/my-tasks')
  })

  it.each(ADMIN_LABELS)('hides %s from a non-Admin', (label) => {
    renderSidebarAs(OB_MEMBER, '/onboarding/dashboard')

    expect(within(obNav()).queryByRole('link', { name: label })).not.toBeInTheDocument()
  })

  it.each(ADMIN_LABELS)('still shows %s to an Admin', (label) => {
    renderSidebarAs(OB_ADMIN, '/onboarding/dashboard')

    expect(within(obNav()).getByRole('link', { name: label })).toBeInTheDocument()
  })

  /**
   * The heading is filtered on the same flag as its rows. Left exempt — as it
   * was while no section was gated — it would render over nothing, naming
   * seven screens the viewer cannot reach instead of omitting them.
   */
  it('drops the Administration heading along with its rows', () => {
    renderSidebarAs(OB_MEMBER, '/onboarding/dashboard')

    expect(within(obNav()).queryByText('Administration')).not.toBeInTheDocument()
  })

  it('keeps the Administration heading for an Admin', () => {
    renderSidebarAs(OB_ADMIN, '/onboarding/dashboard')

    expect(within(obNav()).getByText('Administration')).toBeInTheDocument()
  })

  /**
   * The ticketing rail was not part of this change. Its own division —
   * Masters and Audit log — is asserted above; this pins that a Developer did
   * not lose Tickets, My Tasks or Settings as collateral, which is what a
   * filter applied to the wrong constant would do.
   */
  it('leaves the ticketing rail untouched for a non-Admin', () => {
    renderSidebarAs(OB_MEMBER, '/dashboard')

    for (const label of ['My Tasks', 'Stage Queue', 'Tickets', 'Chat', 'Timesheet', 'Settings']) {
      expect(within(nav()).getByRole('link', { name: label })).toBeInTheDocument()
    }
  })
})

/**
 * The rail opens collapsed to its icons, names each page on hover, and offers
 * expand/collapse above the menu.
 *
 * <p>Collapsed is now the default, so every test above already runs against
 * the icon rail — which is why the labels must survive collapse as `sr-only`
 * text rather than leave the DOM: a link with no accessible name is a link
 * nobody using a screen reader can find, and `getByRole('link', { name })`
 * would have been the first to say so.
 */
describe('Sidebar · collapsed by default', () => {
  it('opens collapsed, with the expand control above the menu', () => {
    renderSidebarAs(DEVELOPER)

    const toggle = screen.getByRole('button', { name: 'Expand sidebar' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    // The control precedes the menu in the document, not follows it.
    expect(toggle.compareDocumentPosition(nav()) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0)
  })

  it('keeps every page name for assistive tech while only the icon is drawn', () => {
    renderSidebarAs(DEVELOPER)

    const tickets = within(nav()).getByRole('link', { name: 'Tickets' })
    expect(within(tickets).getByText('Tickets')).toHaveClass('sr-only')
  })

  it('shows the page name in a tooltip on hover', async () => {
    renderSidebarAs(DEVELOPER)

    await userEvent.hover(within(nav()).getByRole('link', { name: 'Tickets' }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Tickets')
  })

  it('expands on the toggle and draws the labels inline', async () => {
    renderSidebarAs(DEVELOPER)

    await userEvent.click(screen.getByRole('button', { name: 'Expand sidebar' }))

    const toggle = screen.getByRole('button', { name: 'Collapse sidebar' })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const tickets = within(nav()).getByRole('link', { name: 'Tickets' })
    expect(within(tickets).getByText('Tickets')).not.toHaveClass('sr-only')
  })

  it('collapses again on the same control', async () => {
    useSidebarStore.setState({ collapsed: false })
    renderSidebarAs(DEVELOPER)

    await userEvent.click(screen.getByRole('button', { name: 'Collapse sidebar' }))

    expect(screen.getByRole('button', { name: 'Expand sidebar' })).toBeInTheDocument()
  })

  /** The section heading is a rule while collapsed, but its name is not lost. */
  it('keeps the Administration heading readable while collapsed', () => {
    renderSidebarAs(OB_ADMIN, '/onboarding/dashboard')

    expect(within(obNav()).getByText('Administration')).toHaveClass('sr-only')
  })
})
