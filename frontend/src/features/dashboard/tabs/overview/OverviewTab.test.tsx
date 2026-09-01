import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'

import { server } from '@/mocks/server'

import { useDrillDownStore } from '../../drillDownStore'
import { OverviewTab } from './OverviewTab'

/**
 * S-05 tab 2 · the four cards, the Top Assignees bars and the status donut.
 *
 * Rendered against the real MSW handler rather than a mocked hook, matching
 * `WeeklyTab.test.tsx`'s reasoning: mocking the generated hook would prove the
 * component renders a fixture, which was never the half in doubt.
 *
 * The assertions that matter here are the ones the plan's "done means" calls
 * out — that every segment, arc and legend row is reachable by keyboard and
 * opens the drill-down the server built, and that a figure with no expressible
 * list renders as text rather than as a control nobody can use.
 */

function renderTab(initialUrl = '/dashboard?tab=overview') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialUrl]}>
        <OverviewTab />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const cardsSection = () => screen.getByRole('region', { name: 'Ticket overview figures' })
const assigneesPanel = () => screen.getByRole('region', { name: 'Top assignees' })
const donutPanel = () => screen.getByRole('region', { name: 'Ticket status distribution' })

/** One deterministic payload, so an assertion about arithmetic is not at the mercy of the seeded db. */
const PAYLOAD = {
  asOf: '2026-09-01T06:00:00Z',
  unavailableReason: null,
  cards: [
    { key: 'total', label: 'Total', value: 128, drillDown: '/tickets?reportedFrom=2026-08-01' },
    { key: 'pending', label: 'Pending', value: 47, drillDown: '/tickets?statusCategory=TODO' },
    {
      key: 'in-progress',
      label: 'In Progress',
      value: 22,
      drillDown: '/tickets?statusCategory=IN_PROGRESS',
    },
    { key: 'completed', label: 'Completed', value: 59, drillDown: '/tickets?status=CLOSED' },
  ],
  assignees: [
    {
      userId: 1,
      displayName: 'Ravi Kumar',
      inProgress: { value: 2, drillDown: '/tickets?assigneeId=1&statusCategory=IN_PROGRESS' },
      overdue: { value: 4, drillDown: '/tickets?assigneeId=1&isDelayed=true' },
      notStarted: { value: 1, drillDown: '/tickets?assigneeId=1&statusCategory=TODO' },
    },
    {
      userId: 2,
      displayName: 'Priya Nair',
      inProgress: { value: 1, drillDown: '/tickets?assigneeId=2&statusCategory=IN_PROGRESS' },
      // Zero segments must draw nothing at all — not a zero-width control.
      overdue: { value: 0, drillDown: '/tickets?assigneeId=2&isDelayed=true' },
      notStarted: { value: 0, drillDown: '/tickets?assigneeId=2&statusCategory=TODO' },
    },
  ],
  distribution: [
    {
      category: 'TODO',
      label: 'Pending',
      value: 47,
      pct: 36.7,
      drillDown: '/tickets?statusCategory=TODO',
    },
    {
      category: 'IN_PROGRESS',
      label: 'In Progress',
      value: 22,
      pct: 17.2,
      drillDown: '/tickets?statusCategory=IN_PROGRESS',
    },
    {
      category: 'DONE',
      label: 'Completed',
      value: 59,
      pct: 46.1,
      drillDown: '/tickets?status=CLOSED',
    },
  ],
}

function serveOverview(body: unknown = PAYLOAD) {
  server.use(http.get('/api/v1/dashboard/overview', () => HttpResponse.json({ data: body })))
}

beforeEach(() => {
  useDrillDownStore.setState({ drillDown: null, title: '', count: null })
})

describe('OverviewTab — the four cards', () => {
  it('renders every card the endpoint sends, with the server label', async () => {
    serveOverview()
    renderTab()

    const section = within(await screen.findByRole('region', { name: 'Ticket overview figures' }))
    expect(await section.findByText('Total')).toBeInTheDocument()
    expect(section.getByText('128')).toBeInTheDocument()
    expect(section.getByText('Pending')).toBeInTheDocument()
    expect(section.getByText('47')).toBeInTheDocument()
    expect(section.getByText('In Progress')).toBeInTheDocument()
    expect(section.getByText('Completed')).toBeInTheDocument()
  })

  it('opens the drill-down the server built, not one rebuilt here', async () => {
    serveOverview()
    renderTab()

    const card = await screen.findByRole('link', { name: 'Pending: 47' })
    await userEvent.click(card)

    expect(useDrillDownStore.getState().drillDown).toBe('/tickets?statusCategory=TODO')
    expect(useDrillDownStore.getState().count).toBe(47)
  })

  it('renders a card with no drill-down as text rather than a dead link', async () => {
    serveOverview({
      ...PAYLOAD,
      cards: [{ key: 'total', label: 'Total', value: 12, drillDown: null }],
    })
    renderTab()

    expect(await within(cardsSection()).findByText('12')).toBeInTheDocument()
    expect(within(cardsSection()).queryByRole('link')).not.toBeInTheDocument()
  })
})

describe('OverviewTab — Top Assignees', () => {
  it('draws one keyboard-reachable button per non-zero segment', async () => {
    serveOverview()
    renderTab()

    const panel = within(await screen.findByRole('region', { name: 'Top assignees' }))
    // Ravi has three non-zero segments; Priya has one. Four in total, and the
    // two zero segments contribute nothing.
    const segments = await panel.findAllByRole('button')
    expect(segments).toHaveLength(4)

    expect(
      panel.getByRole('button', {
        name: 'Ravi Kumar — Overdue: 4. Open the filtered ticket list.',
      }),
    ).toBeInTheDocument()
    expect(
      panel.queryByRole('button', { name: /Priya Nair — Overdue/ }),
    ).not.toBeInTheDocument()
  })

  it('prints each row total as the sum of its three disjoint segments', async () => {
    serveOverview()
    renderTab()

    const panel = within(await screen.findByRole('region', { name: 'Top assignees' }))
    // Ravi: 2 in progress + 4 overdue + 1 not started.
    expect(await panel.findByText('7')).toBeInTheDocument()
    expect(panel.getByText('1')).toBeInTheDocument()
  })

  it('opens a segment by keyboard alone', async () => {
    serveOverview()
    renderTab()

    const segment = await screen.findByRole('button', {
      name: 'Ravi Kumar — Overdue: 4. Open the filtered ticket list.',
    })
    segment.focus()
    expect(segment).toHaveFocus()
    await userEvent.keyboard('{Enter}')

    expect(useDrillDownStore.getState().drillDown).toBe('/tickets?assigneeId=1&isDelayed=true')
  })

  it('says so plainly when nobody holds open work', async () => {
    serveOverview({ ...PAYLOAD, assignees: [] })
    renderTab()

    expect(
      await within(assigneesPanel()).findByText(/Nobody is holding open tickets/),
    ).toBeInTheDocument()
  })
})

describe('OverviewTab — status distribution', () => {
  it('draws one arc per bucket and serves the total in the centre', async () => {
    serveOverview()
    const { container } = renderTab()

    // Waiting on the legend, not on the panel: the panel renders during
    // loading too (it wraps the skeleton), so awaiting it would let the
    // assertions run before any data arrived.
    await screen.findByRole('button', {
      name: 'Completed: 59, 46.1%. Open the filtered ticket list.',
    })

    expect(container.querySelectorAll('svg path')).toHaveLength(3)
    // 47 + 22 + 59, drawn in the middle of the half-donut.
    expect(within(donutPanel()).getByText('128')).toBeInTheDocument()
    expect(within(donutPanel()).getByText('TOTAL TICKETS')).toBeInTheDocument()
  })

  /**
   * The deviation this component documents: an arc is not a second control
   * announcing the same destination as its legend row. Pinned as a test
   * because the first cut did exactly that and the duplicate names are only
   * visible from here.
   */
  it('exposes the drawing as one image, never as a second set of controls', async () => {
    serveOverview()
    const { container } = renderTab()

    const panel = within(await screen.findByRole('region', { name: 'Ticket status distribution' }))
    await panel.findByText('36.7%')

    expect(container.querySelectorAll('path[role="button"]')).toHaveLength(0)
    expect(
      panel.getByRole('img', { name: /Status distribution: Pending 47, In Progress 22/ }),
    ).toBeInTheDocument()
    // Exactly three controls in the panel — the legend rows, one per bucket.
    expect(panel.getAllByRole('button')).toHaveLength(3)
  })

  it("renders the server's pct rather than recomputing it", async () => {
    serveOverview()
    renderTab()

    const panel = within(await screen.findByRole('region', { name: 'Ticket status distribution' }))
    // 47/128 is 36.71875 — the server's one-decimal figure, not a re-rounding.
    expect(await panel.findByText('36.7%')).toBeInTheDocument()
    expect(panel.getByText('17.2%')).toBeInTheDocument()
    expect(panel.getByText('46.1%')).toBeInTheDocument()
  })

  it('opens the drill-down from a legend row', async () => {
    serveOverview()
    renderTab()

    const legendRow = await screen.findByRole('button', {
      name: 'Completed: 59, 46.1%. Open the filtered ticket list.',
    })
    await userEvent.click(legendRow)

    expect(useDrillDownStore.getState().drillDown).toBe('/tickets?status=CLOSED')
    expect(useDrillDownStore.getState().count).toBe(59)
  })

  it('reaches every bucket by keyboard alone, through the legend', async () => {
    serveOverview()
    renderTab()

    const first = await screen.findByRole('button', {
      name: 'Pending: 47, 36.7%. Open the filtered ticket list.',
    })
    first.focus()
    expect(first).toHaveFocus()
    await userEvent.keyboard('{Enter}')

    expect(useDrillDownStore.getState().drillDown).toBe('/tickets?statusCategory=TODO')
  })

  it('says so plainly when the range holds nothing', async () => {
    serveOverview({
      ...PAYLOAD,
      distribution: [
        { category: 'TODO', label: 'Pending', value: 0, pct: 0, drillDown: null },
        { category: 'IN_PROGRESS', label: 'In Progress', value: 0, pct: 0, drillDown: null },
        { category: 'DONE', label: 'Completed', value: 0, pct: 0, drillDown: null },
      ],
    })
    renderTab()

    expect(await within(donutPanel()).findByText(/No tickets in this range/)).toBeInTheDocument()
  })
})

describe('OverviewTab — the two refusal branches', () => {
  it('explains an out-of-scope project in the server’s own words', async () => {
    serveOverview({
      asOf: null,
      unavailableReason: 'That project is not one of yours.',
      cards: [],
      assignees: [],
      distribution: [],
    })
    renderTab('/dashboard?tab=overview&projectId=99')

    expect(await screen.findByText('That project is not one of yours.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Ticket overview figures' })).not.toBeInTheDocument()
  })

  it('offers a retry when the endpoint fails', async () => {
    server.use(http.get('/api/v1/dashboard/overview', () => HttpResponse.error()))
    renderTab()

    expect(await screen.findByText('Ticket Overview could not be loaded')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('shows the staleness notice from the served asOf', async () => {
    serveOverview()
    renderTab()

    expect(await screen.findByText(/Figures as at/)).toBeInTheDocument()
  })
})
