import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Me } from '@/api/generated/model/me'
import { initialAuthState, useAuthStore } from '@/features/auth/authStore'

import { DashboardWidgets } from './DashboardWidgets'

/**
 * A-062 · §S-05's developer dashboard is a *smaller screen*, not a differently
 * filtered one.
 *
 * "The Developer's dashboard shows only widgets 1–6, 9, 12 scoped to
 * `assignee = me`, plus My due today / this week." Cards 1–6 come from the
 * server as a card list — `DashboardScopeIT` pins that side — so what is left to
 * assert here is the part only the client decides: how many boxes get drawn, and
 * for whom.
 *
 * Before this, a delivery role loaded all nine widgets and got six panels of
 * "this breakdown is not kept per resource". Each sentence was true and the
 * screen read as broken.
 */

/**
 * Widgets 7–20 — every chart `DashboardWidgets` renders for a manager.
 *
 * Named rather than written as a literal because it keeps moving: A-057 made it
 * nine, A-059 ten, and A-058 fourteen by adding the four the Workflow Ribbon
 * unlocks — the stage funnel, rework, time per stage and handoff latency. A
 * bare `14` in two assertions says nothing about which widgets were added or
 * whether adding them was intended.
 *
 * What these two tests are actually about is that a manager's list is *not* the
 * delivery role's short one — and A-058 widens the gap rather than changing the
 * rule, since all four of its widgets are keyed by project and refuse a
 * delivery role in words.
 */
const MANAGER_WIDGETS = 14

const useGetDashboardWidget = vi.fn()
const useGetDashboardWidgets = vi.fn()
vi.mock('@/api/generated/dashboard/dashboard', () => ({
  useGetDashboardWidget: (...args: unknown[]) => useGetDashboardWidget(...args),
  useGetDashboardWidgets: (...args: unknown[]) => useGetDashboardWidgets(...args),
}))

/** Every frame renders its title before its request settles, which is what these assert on. */
function pending() {
  useGetDashboardWidget.mockReturnValue({ data: undefined, isPending: true, isError: false })
  useGetDashboardWidgets.mockReturnValue({ data: undefined, isPending: true, isError: false })
}

function signedInAs(role: string | undefined) {
  useAuthStore.setState({
    ...initialAuthState,
    status: 'authenticated',
    user: { id: 7, displayName: 'Test', role } as Me,
  })
}

function renderWidgets() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <DashboardWidgets params={{}} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/**
 * The key of every widget the grid asked the server for, in render order.
 *
 * A-073 · read off the batch call rather than the per-widget one. The grid now
 * asks for its whole variant in a single request, so the keys it sends are the
 * `keys` parameter — and reading them from the per-widget hook would still
 * "pass" while asserting nothing about what was fetched, because `useWidget`
 * calls that hook for every frame regardless and merely disables it inside a
 * batch. Asserting on the disabled call would be the quietest possible way for
 * this suite to stop testing the thing it exists for.
 */
function requestedKeys(): string[] {
  const calls = useGetDashboardWidgets.mock.calls
  if (calls.length === 0) {
    return []
  }
  return ((calls[0][0] as { keys?: string[] }).keys ?? []).slice()
}

beforeEach(() => {
  vi.clearAllMocks()
  useAuthStore.setState(initialAuthState)
  pending()
})

describe('the developer dashboard variant', () => {
  it('asks for widgets 9 and 12 only', () => {
    signedInAs('DEVELOPER')
    renderWidgets()

    expect(requestedKeys()).toEqual(['velocity', 'aging-buckets'])
  })

  /**
   * The point of the task, stated as the thing that must not come back. Each of
   * these has a per-resource answer that does not exist — asking for them
   * produced the six sentences the variant exists to remove, and the cost is
   * six requests as well as six panels.
   */
  it('does not ask for the widgets a delivery role has no table for', () => {
    signedInAs('DEVELOPER')
    renderWidgets()

    expect(requestedKeys()).not.toContain('type-donut')
    expect(requestedKeys()).not.toContain('priority-bar')
    expect(requestedKeys()).not.toContain('sla-gauge')
    expect(requestedKeys()).not.toContain('project-treemap')
    // A-059 · widget 20 joined the manager's list and not this one. Client
    // volume is keyed by project and client, and "which clients raised the
    // tickets assigned to me" is a question nothing records.
    expect(requestedKeys()).not.toContain('client-volume')
  })

  it('titles both widgets as the reader’s own work', () => {
    signedInAs('QA')
    renderWidgets()

    expect(screen.getByText(/My velocity/)).toBeInTheDocument()
    expect(screen.getByText(/My ticket aging/)).toBeInTheDocument()
  })

  it.each(['DEVELOPER', 'QA', 'DEPLOYMENT'])('applies to %s', (role) => {
    signedInAs(role)
    renderWidgets()

    expect(requestedKeys()).toHaveLength(2)
  })

  it.each(['ADMIN', 'PM', 'SUPPORT'])('leaves %s on the full set', (role) => {
    signedInAs(role)
    renderWidgets()

    expect(requestedKeys()).toHaveLength(MANAGER_WIDGETS)
  })

  /**
   * Before the startup refresh has answered there is no role, and the fallback
   * is the full layout. That is the safe direction for a *layout* default: every
   * widget is drawn and each one is answered, or refused, by the server on its
   * own merits. Defaulting the other way would hide widgets from an Admin for
   * the first few hundred milliseconds of every reload.
   */
  it('falls back to the full layout while the role is unknown', () => {
    useAuthStore.setState(initialAuthState)
    renderWidgets()

    expect(requestedKeys()).toHaveLength(MANAGER_WIDGETS)
  })
})
