import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ObImplementorWorkloadGrid } from './ObImplementorWorkloadGrid'

/**
 * B-128 · the grid in isolation, against a mocked
 * `useListObImplementorWorkload` — {@code ObDashboardDrillPanel.test.tsx}'s
 * own shape. What is under test is this component's own rendering and the
 * `onDrill` handoff into {@code ObDashboardDrillPanel}; the query itself is
 * `ObImplementorWorkloadIT`'s job.
 */

const useListObImplementorWorkload = vi.fn()
vi.mock('@/api/generated/onboarding/onboarding', () => ({
  useListObImplementorWorkload: (...args: unknown[]) => useListObImplementorWorkload(...args),
}))

const PRIYA = {
  user: { id: 7, displayName: 'Priya Iyer' },
  isActive: true,
  clientsOpen: 5,
  onTrack: 2,
  notStarted: 1,
  delayed: 1,
  atRisk: 1,
  blockedWaiting: 0,
  aheadOfSchedule: 0,
  completedOnTime: 3,
  completedEarly: 1,
  completedLate: 0,
  blockedHours: 0,
  performanceScore: 90,
  statDate: '2026-09-02',
}

/** The bench — zero clients, still a row. */
const ARJUN = {
  ...PRIYA,
  user: { id: 9, displayName: 'Arjun Nair' },
  clientsOpen: 0,
  onTrack: 0,
  notStarted: 0,
  delayed: 0,
  atRisk: 0,
  blockedWaiting: 0,
  aheadOfSchedule: 0,
  completedOnTime: 0,
  completedEarly: 0,
  completedLate: 0,
  performanceScore: null,
}

function served(data: unknown, meta: Record<string, unknown> = {}) {
  useListObImplementorWorkload.mockReturnValue({
    data: data === undefined ? undefined : { data, meta: { hasMore: false, ...meta } },
    isPending: false,
    isError: false,
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  served([PRIYA, ARJUN])
})

describe('ObImplementorWorkloadGrid', () => {
  it('renders one row per implementor, the bench included at zero', () => {
    render(<ObImplementorWorkloadGrid onDrill={vi.fn()} />)

    const bench = screen.getByRole('row', { name: /Arjun Nair/ })
    expect(bench).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Priya Iyer's open clients: 5/ })).toBeInTheDocument()
  })

  it('renders a dash rather than a zero for a null performanceScore', () => {
    render(<ObImplementorWorkloadGrid onDrill={vi.fn()} />)

    const bench = screen.getByRole('row', { name: /Arjun Nair/ })
    expect(bench).toHaveTextContent('—')
  })

  it('formats a real performanceScore to one decimal place', () => {
    render(<ObImplementorWorkloadGrid onDrill={vi.fn()} />)

    expect(screen.getByText('90.0')).toBeInTheDocument()
  })

  it("clicking the delayed count opens the overdue-clients card, narrowed to that implementor", async () => {
    const onDrill = vi.fn()
    render(<ObImplementorWorkloadGrid onDrill={onDrill} />)

    await userEvent.click(screen.getByRole('button', { name: /Priya Iyer's delayed clients: 1/ }))

    expect(onDrill).toHaveBeenCalledWith({
      cardKey: 'overdue-clients',
      ownerUserId: 7,
      title: 'Priya Iyer · Delayed',
    })
  })

  it("clicking the at-risk count opens the at-risk card, narrowed to that implementor", async () => {
    const onDrill = vi.fn()
    render(<ObImplementorWorkloadGrid onDrill={onDrill} />)

    await userEvent.click(screen.getByRole('button', { name: /Priya Iyer's at-risk clients: 1/ }))

    expect(onDrill).toHaveBeenCalledWith({
      cardKey: 'at-risk',
      ownerUserId: 7,
      title: 'Priya Iyer · At risk',
    })
  })

  it('the three completion counters are not columns — plan §9 names six status counts and a score, not nine', () => {
    render(<ObImplementorWorkloadGrid onDrill={vi.fn()} />)

    expect(screen.queryByText('3')).not.toBeInTheDocument() // completedOnTime, unrendered
    expect(screen.queryByRole('columnheader', { name: /completed/i })).not.toBeInTheDocument()
  })

  it('shows an empty state when no implementor holds a grant', () => {
    served([])
    render(<ObImplementorWorkloadGrid onDrill={vi.fn()} />)

    expect(screen.getByText('No implementor holds an onboarding grant yet')).toBeInTheDocument()
  })

  it('shows an error state distinct from the empty one', () => {
    useListObImplementorWorkload.mockReturnValue({ data: undefined, isPending: false, isError: true })
    render(<ObImplementorWorkloadGrid onDrill={vi.fn()} />)

    expect(screen.getByText('The workload grid could not be loaded')).toBeInTheDocument()
  })

  it('names the grid for a screen-reader user via its own heading, and uses real table semantics', () => {
    render(<ObImplementorWorkloadGrid onDrill={vi.fn()} />)

    expect(screen.getByRole('heading', { name: /Implementor workload/ })).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getAllByRole('columnheader').length).toBeGreaterThan(5)
  })
})
