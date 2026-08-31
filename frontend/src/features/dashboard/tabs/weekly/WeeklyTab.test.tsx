import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { MemoryRouter, useSearchParams } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { server } from '@/mocks/server'

import { formatValue } from './weeklyCardFormat'
import { WeeklyTab } from './WeeklyTab'

/**
 * S-05 tab 3 · the picker and the four cards.
 *
 * Rendered against the real MSW handler rather than a mocked hook, so these
 * assert the wiring end to end — the URL the picker writes, the `weekStart` the
 * query sends, and the shape the handler returns. A test that mocks
 * `useGetDashboardWeekly` proves the component renders a fixture, which is the
 * half that was never in doubt.
 */

/** Echoes the current query string, so a test can assert on the URL the tab wrote. */
function UrlProbe() {
  const [params] = useSearchParams()
  return <output data-testid="url">{params.toString()}</output>
}

function renderTab(initialUrl = '/dashboard?tab=weekly') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialUrl]}>
        <WeeklyTab />
        <UrlProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const cardsSection = () => screen.getByRole('region', { name: 'Weekly figures' })

describe('WeeklyTab — the four cards', () => {
  it('renders the four weekly cards from the endpoint', async () => {
    renderTab()

    // `findByText` waits on its own; an extra waitFor for "any text at all"
    // only added a second timeout that skeletons could outlast under load.
    for (const label of ['Average progress', 'Due this week', 'Delayed', 'Average delay']) {
      expect(await within(cardsSection()).findByText(label)).toBeInTheDocument()
    }
  })

  it('sends the selected weekStart to the server', async () => {
    let seen: string | null = null
    server.use(
      http.get('/api/v1/dashboard/weekly', ({ request }) => {
        seen = new URL(request.url).searchParams.get('weekStart')
        return HttpResponse.json({
          data: {
            asOf: '2026-08-31T09:00:00.000Z',
            weekStart: '2026-08-24',
            weekEnd: '2026-08-30',
            cards: [],
          },
        })
      }),
    )

    renderTab('/dashboard?tab=weekly&weekStart=2026-08-24')
    await waitFor(() => expect(seen).toBe('2026-08-24'))
  })

  it('shows the as-of line, because these figures are stale by design', async () => {
    renderTab()
    expect(await screen.findByText(/Refreshed every five minutes/)).toBeInTheDocument()
  })
})

describe('WeeklyTab — the week picker', () => {
  it('offers this week and last week, with the current one pressed', async () => {
    renderTab()
    const group = screen.getByRole('group', { name: 'Week' })
    const options = within(group).getAllByRole('button')
    expect(options.map((b) => b.textContent)).toEqual(['This week', 'Last week'])
    expect(options[0]).toHaveAttribute('aria-pressed', 'true')
    expect(options[1]).toHaveAttribute('aria-pressed', 'false')
  })

  it('writes weekStart into the URL when a week is chosen', async () => {
    const user = userEvent.setup()
    renderTab()

    await user.click(screen.getByRole('button', { name: 'Last week' }))

    await waitFor(() => {
      expect(screen.getByTestId('url').textContent).toMatch(/weekStart=\d{4}-\d{2}-\d{2}/)
    })
    // Whatever week it wrote, it must be a Monday — the endpoint 400s otherwise.
    const written = new URLSearchParams(screen.getByTestId('url').textContent ?? '').get('weekStart')
    expect(new Date(`${written}T00:00:00Z`).getUTCDay()).toBe(1)
  })

  it('keeps the tab parameter when changing week', async () => {
    const user = userEvent.setup()
    renderTab('/dashboard?tab=weekly&projectId=4')

    await user.click(screen.getByRole('button', { name: 'Last week' }))

    await waitFor(() => {
      const params = new URLSearchParams(screen.getByTestId('url').textContent ?? '')
      expect(params.get('tab')).toBe('weekly')
      expect(params.get('projectId')).toBe('4')
    })
  })
})

describe('WeeklyTab — a weekStart the server would refuse', () => {
  it('explains a non-Monday rather than silently showing another week', async () => {
    renderTab('/dashboard?tab=weekly&weekStart=2026-09-02')

    expect(await screen.findByText('That is not the start of a week')).toBeInTheDocument()
    expect(screen.getByText(/"2026-09-02" is not one/)).toBeInTheDocument()
    // No cards are drawn: showing figures for a different week than the URL
    // names is the failure this branch exists to prevent.
    expect(screen.queryByRole('region', { name: 'Weekly figures' })).not.toBeInTheDocument()
  })

  it('offers a way back to this week', async () => {
    const user = userEvent.setup()
    renderTab('/dashboard?tab=weekly&weekStart=2026-09-02')

    await user.click(await screen.findByRole('button', { name: 'Show this week' }))

    await waitFor(() => expect(screen.getByRole('group', { name: 'Week' })).toBeInTheDocument())
  })

  it('treats nonsense the same way', async () => {
    renderTab('/dashboard?tab=weekly&weekStart=not-a-date')
    expect(await screen.findByText('That is not the start of a week')).toBeInTheDocument()
  })

  it('defaults to this week when weekStart is absent', async () => {
    renderTab('/dashboard?tab=weekly')
    expect(await screen.findByRole('group', { name: 'Week' })).toBeInTheDocument()
    expect(screen.queryByText('That is not the start of a week')).not.toBeInTheDocument()
  })
})

describe('WeeklyTab — states the server can report', () => {
  const withPayload = (data: Record<string, unknown>) =>
    server.use(http.get('/api/v1/dashboard/weekly', () => HttpResponse.json({ data })))

  it('renders unavailableReason instead of a wall of zeroes', async () => {
    withPayload({
      asOf: null,
      weekStart: '2026-08-31',
      weekEnd: '2026-09-06',
      cards: [],
      unavailableReason: 'These figures cover the projects you are a member of.',
    })
    renderTab()

    expect(await screen.findByText('These figures are not available')).toBeInTheDocument()
    expect(
      screen.getByText('These figures cover the projects you are a member of.'),
    ).toBeInTheDocument()
  })

  it('offers a retry when the request fails', async () => {
    server.use(http.get('/api/v1/dashboard/weekly', () => HttpResponse.error()))
    renderTab()

    expect(await screen.findByText('Weekly progress could not be loaded')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('says the figures have not been computed when asOf is null', async () => {
    withPayload({ asOf: null, weekStart: '2026-08-31', weekEnd: '2026-09-06', cards: [] })
    renderTab()

    expect(await screen.findByText(/have not been computed yet/)).toBeInTheDocument()
  })
})

describe('WeeklyCard — the delta', () => {
  const oneCard = (card: Record<string, unknown>) =>
    server.use(
      http.get('/api/v1/dashboard/weekly', () =>
        HttpResponse.json({
          data: {
            asOf: '2026-08-31T09:00:00.000Z',
            weekStart: '2026-08-31',
            weekEnd: '2026-09-06',
            cards: [card],
          },
        }),
      ),
    )

  it('renders null as an absence, never as 0%', async () => {
    oneCard({ key: 'avg-progress', label: 'Average progress', value: 42, unit: 'PERCENT', deltaPct: null })
    renderTab()

    expect(await screen.findByText('No prior week to compare')).toBeInTheDocument()
    expect(screen.queryByText(/0% on last week/)).not.toBeInTheDocument()
  })

  it('distinguishes a genuine zero from no data', async () => {
    oneCard({ key: 'avg-progress', label: 'Average progress', value: 42, unit: 'PERCENT', deltaPct: 0 })
    renderTab()

    expect(await screen.findByText('Unchanged on last week')).toBeInTheDocument()
    expect(screen.queryByText('No prior week to compare')).not.toBeInTheDocument()
  })

  it('shows direction and sign', async () => {
    oneCard({ key: 'delayed-vs-last-week', label: 'Delayed', value: 9, unit: 'COUNT', deltaPct: 12.5 })
    renderTab()

    expect(await screen.findByText(/\+12\.5% on last week/)).toBeInTheDocument()
  })

  it('renders a second figure where the card carries one', async () => {
    oneCard({
      key: 'due-this-week',
      label: 'Due this week',
      value: 20,
      unit: 'COUNT',
      secondaryValue: 7,
      secondaryLabel: 'finished so far',
      deltaPct: null,
    })
    renderTab()

    expect(await screen.findByText('7 finished so far')).toBeInTheDocument()
  })

  it('is not a link when the figure has no drill-down', async () => {
    oneCard({ key: 'avg-delay-days', label: 'Average delay', value: 3.2, unit: 'DAYS', drillDown: null })
    renderTab()

    await screen.findByText('Average delay')
    expect(screen.queryByRole('link', { name: /Average delay/ })).not.toBeInTheDocument()
  })

  it('is a link when it has one, so it can be opened in a new tab', async () => {
    oneCard({
      key: 'avg-delay-days',
      label: 'Average delay',
      value: 3.2,
      unit: 'DAYS',
      drillDown: '/tickets?isDelayed=true&excludeClosed=true',
    })
    renderTab()

    const link = await screen.findByRole('link', { name: /Average delay/ })
    expect(link).toHaveAttribute('href', '/tickets?isDelayed=true&excludeClosed=true')
  })
})

describe('formatValue', () => {
  it.each([
    [42.55, 'PERCENT', '42.6%'],
    [42, 'PERCENT', '42%'],
    [1, 'DAYS', '1 day'],
    [3.24, 'DAYS', '3.2 days'],
    [0, 'DAYS', '0 days'],
    [19.6, 'COUNT', '20'],
  ])('formats %f as %s → %s', (value, unit, expected) => {
    expect(formatValue(value, unit)).toBe(expected)
  })
})
