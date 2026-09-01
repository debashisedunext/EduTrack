import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'

import { server } from '@/mocks/server'

import { useDrillDownStore } from '../../drillDownStore'
import { TodayTab } from './TodayTab'

/**
 * S-05 tab 1 · PR 7 — the seven cards and the Open Issues card.
 *
 * Rendered against the real MSW handler for the default (OWN_WORK) case, the
 * same reason `WeeklyTab.test.tsx` does: a test that mocks the query hook
 * proves the component renders a fixture, which was never in doubt. The
 * FULL-variant and edge-case tests below override that handler explicitly,
 * because the seeded db's current user is a Developer (OWN_WORK) and the
 * Open Issues card only exists on the other shape.
 */

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/dashboard?tab=today']}>
        <TodayTab />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const cardsSection = () => screen.getByRole('region', { name: "Today's figures" })

const fig = (value: number, drillDown: string | null = null) => ({ value, drillDown })

beforeEach(() => {
  useDrillDownStore.setState({ drillDown: null, title: '', count: null })
})

describe('TodayTab — the seven cards', () => {
  it('renders all seven cards from the endpoint', async () => {
    renderTab()

    // `getByRole('group', { name })` rather than `findByText`: two of the
    // real handler's own figures are labelled "WIP" and "Overdue", the same
    // text as two of the card titles — `findByText` would find both and
    // throw on ambiguity. Each card is its own named group precisely so a
    // test (and a screen reader) can address the card without that clash.
    for (const label of [
      "Today's Work",
      'Overdue',
      'Not Started',
      'WIP',
      'WIP Breakdown',
      'Blocked',
      'Pending Review',
    ]) {
      expect(await within(cardsSection()).findByRole('group', { name: label })).toBeInTheDocument()
    }
  })

  it('shows the as-of line, because these figures are stale by design', async () => {
    renderTab()
    expect(await screen.findByText(/Refreshed every five minutes/)).toBeInTheDocument()
  })

  it('does not render the Open Issues card on the OWN_WORK variant', async () => {
    renderTab()
    await within(cardsSection()).findByRole('group', { name: "Today's Work" })
    expect(screen.queryByRole('group', { name: 'Open Issues' })).not.toBeInTheDocument()
  })
})

describe('TodayTab — a sub-figure', () => {
  const oneCard = () =>
    server.use(
      http.get('/api/v1/dashboard/today', () =>
        HttpResponse.json({
          data: {
            asOf: '2026-09-01T09:00:00.000Z',
            variant: 'FULL',
            unavailableReason: null,
            cards: [
              {
                key: 'wip-breakdown',
                label: 'WIP Breakdown',
                total: fig(22, '/tickets?statusCategory=IN_PROGRESS'),
                figures: [
                  { key: 'near-delay', label: 'Near delay', value: 4, drillDown: '/tickets?statusCategory=IN_PROGRESS&dueTo=2026-09-02' },
                  { key: 'delayed', label: 'Delayed', value: 7, drillDown: '/tickets?statusCategory=IN_PROGRESS&isDelayed=true' },
                  { key: 'on-time', label: 'On time', value: 11, drillDown: null },
                ],
              },
            ],
            openIssues: null,
            resources: [],
          },
        }),
      ),
    )

  it('opens the drill-down panel with the figure it counted', async () => {
    oneCard()
    renderTab()

    await userEvent.click(await screen.findByRole('button', { name: /^Delayed: 7\./ }))

    expect(useDrillDownStore.getState().drillDown).toBe('/tickets?statusCategory=IN_PROGRESS&isDelayed=true')
    expect(useDrillDownStore.getState().title).toBe('Delayed')
    expect(useDrillDownStore.getState().count).toBe(7)
  })

  it('is not a button when the figure has no drill-down', async () => {
    oneCard()
    renderTab()

    await screen.findByText('On time')
    expect(screen.queryByRole('button', { name: /^On time: 11/ })).not.toBeInTheDocument()
  })

  it('also opens the panel from the card total when the total has its own drill-down', async () => {
    oneCard()
    renderTab()

    await userEvent.click(await screen.findByRole('button', { name: /^WIP Breakdown total: 22\./ }))

    expect(useDrillDownStore.getState().drillDown).toBe('/tickets?statusCategory=IN_PROGRESS')
    expect(useDrillDownStore.getState().count).toBe(22)
  })
})

describe('TodayTab — a card with no sub-figures', () => {
  it("renders pending-review's total as the card's one figure", async () => {
    server.use(
      http.get('/api/v1/dashboard/today', () =>
        HttpResponse.json({
          data: {
            asOf: '2026-09-01T09:00:00.000Z',
            variant: 'FULL',
            unavailableReason: null,
            cards: [
              {
                key: 'pending-review',
                label: 'Pending Review',
                total: fig(8, '/tickets?pendingReview=true'),
                figures: [],
              },
            ],
            openIssues: null,
            resources: [],
          },
        }),
      ),
    )
    renderTab()

    await userEvent.click(await screen.findByRole('button', { name: /^Total: 8\./ }))
    expect(useDrillDownStore.getState().drillDown).toBe('/tickets?pendingReview=true')
  })
})

describe('TodayTab — the FULL variant', () => {
  const withOpenIssues = () =>
    server.use(
      http.get('/api/v1/dashboard/today', () =>
        HttpResponse.json({
          data: {
            asOf: '2026-09-01T09:00:00.000Z',
            variant: 'FULL',
            unavailableReason: null,
            cards: [],
            openIssues: {
              total: fig(47, '/tickets?excludeClosed=true'),
              roles: [
                { role: 'DEVELOPER', label: 'Developer', value: 18, drillDown: '/tickets?excludeClosed=true' },
                { role: 'UNASSIGNED', label: 'Unassigned', value: 5, drillDown: '/tickets?unassigned=true' },
              ],
            },
            resources: [],
          },
        }),
      ),
    )

  it('renders the Open Issues card with its role chips', async () => {
    withOpenIssues()
    renderTab()

    expect(await screen.findByText('Open Issues')).toBeInTheDocument()
    expect(screen.getByText('Developer 18')).toBeInTheDocument()
    expect(screen.getByText('Unassigned 5')).toBeInTheDocument()
  })

  it('opens the drill-down from a role chip', async () => {
    withOpenIssues()
    renderTab()

    await userEvent.click(await screen.findByRole('button', { name: /^Unassigned: 5\./ }))

    expect(useDrillDownStore.getState().drillDown).toBe('/tickets?unassigned=true')
    expect(useDrillDownStore.getState().title).toBe('Unassigned')
  })
})

describe('TodayTab — states the server can report', () => {
  it('renders unavailableReason instead of a wall of zeroes', async () => {
    server.use(
      http.get('/api/v1/dashboard/today', () =>
        HttpResponse.json({
          data: {
            asOf: null,
            variant: 'FULL',
            unavailableReason: 'These figures cover the projects you are a member of.',
            cards: [],
            openIssues: null,
            resources: [],
          },
        }),
      ),
    )
    renderTab()

    expect(await screen.findByText('These figures are not available')).toBeInTheDocument()
    expect(
      screen.getByText('These figures cover the projects you are a member of.'),
    ).toBeInTheDocument()
  })

  it('offers a retry when the request fails', async () => {
    server.use(http.get('/api/v1/dashboard/today', () => HttpResponse.error()))
    renderTab()

    expect(await screen.findByText("Today's Progress could not be loaded")).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
