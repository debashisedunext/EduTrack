import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { DrillDownPanel } from './DrillDownPanel'
import { KpiCard } from './KpiCard'
import { useDrillDownStore } from './drillDownStore'

/**
 * A-061 · §S-06.
 *
 * Two things are under test and only one of them is the panel. The other is
 * that opening it did not cost the KPI card what A-055 built into it — a card
 * that stops being openable in a new tab has traded a browser affordance for a
 * modal, and nobody would notice until they tried.
 */

const navigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigate }
})

const useListTickets = vi.fn()
vi.mock('@/api/generated/tickets/tickets', () => ({
  useListTickets: (...args: unknown[]) => useListTickets(...args),
  listTickets: vi.fn(),
}))

function renderWith(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

const ROWS = [
  {
    ticketId: 'CRM-26-00011',
    title: 'Payment gateway timeout',
    level: 'CRITICAL',
    assignee: { displayName: 'Ravi Kumar' },
  },
  { ticketId: 'CRM-26-00042', title: 'Login fails on Safari', level: 'HIGH', assignee: null },
]

function served(data: unknown, meta: Record<string, unknown> = {}) {
  useListTickets.mockReturnValue({
    data: { data, meta: { hasMore: false, ...meta } },
    isPending: false,
    isError: false,
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  useDrillDownStore.setState({ drillDown: null, title: '' })
  served(ROWS)
})

describe('KpiCard — opening the panel without losing the link', () => {
  it('opens the panel on a plain click, and does not navigate', async () => {
    renderWith(
      <KpiCard label="Critical" value={18} drillDown="/tickets?level=CRITICAL&excludeClosed=true" />,
    )

    await userEvent.click(screen.getByRole('link', { name: /^Critical: 18\./ }))

    expect(useDrillDownStore.getState().drillDown).toBe('/tickets?level=CRITICAL&excludeClosed=true')
    expect(useDrillDownStore.getState().title).toBe('Critical')
    expect(navigate).not.toHaveBeenCalled()
  })

  /**
   * The behaviour A-055's anchor exists for. Ctrl-click is how somebody opens
   * the filtered list in a second tab while keeping the dashboard in front of
   * them, and an app that swallows it has taken something the browser gave.
   */
  it('leaves a modifier-click alone, so it still opens in a new tab', async () => {
    // `userEvent.setup()` rather than the bare `userEvent.click` helpers: the
    // direct API builds a fresh session per call, so a modifier held by an
    // earlier `keyboard()` is forgotten before the click and the event arrives
    // with `ctrlKey: false`. The first version of this test did exactly that
    // and failed against a guard that works — worth the note, because a test
    // that cannot express the input it claims to send is indistinguishable
    // from a broken guard.
    const user = userEvent.setup()
    renderWith(<KpiCard label="Critical" value={18} drillDown="/tickets?level=CRITICAL" />)

    await user.keyboard('{Control>}')
    await user.click(screen.getByRole('link', { name: /^Critical: 18\./ }))
    await user.keyboard('{/Control}')

    expect(useDrillDownStore.getState().drillDown).toBeNull()
  })

  it('is still announced as a link carrying its value', () => {
    renderWith(<KpiCard label="Delayed" value={26} drillDown="/tickets?isDelayed=true" />)

    expect(
      screen.getByRole('link', { name: 'Delayed: 26. Open the filtered ticket list.' }),
    ).toBeInTheDocument()
  })
})

describe('DrillDownPanel', () => {
  it('renders nothing until something is opened', () => {
    renderWith(<DrillDownPanel />)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('heads itself with what was clicked and describes the filter it fetched', async () => {
    useDrillDownStore.setState({
      drillDown: '/tickets?level=CRITICAL&excludeClosed=true',
      title: 'Critical',
    })
    renderWith(<DrillDownPanel />)

    const panel = await screen.findByRole('dialog')
    expect(within(panel).getByText('Critical')).toBeInTheDocument()
    // Scoped to the visible subtitle. The same description also appears in the
    // table's `sr-only` caption — deliberately, so a screen reader gets the
    // filter without the heading above it — which makes a bare text query
    // ambiguous rather than wrong.
    expect(within(panel).getByText(/critical · still open/, { selector: 'p' })).toBeInTheDocument()
  })

  it('shows the filtered rows', async () => {
    useDrillDownStore.setState({ drillDown: '/tickets?level=CRITICAL', title: 'Critical' })
    renderWith(<DrillDownPanel />)

    const panel = await screen.findByRole('dialog')
    expect(within(panel).getByText('Payment gateway timeout')).toBeInTheDocument()
    expect(within(panel).getByText('Ravi Kumar')).toBeInTheDocument()
    expect(within(panel).getByText('Unassigned')).toBeInTheDocument()
  })

  it('navigates to the same filter behind "Open full list"', async () => {
    const link = '/tickets?level=CRITICAL&excludeClosed=true'
    useDrillDownStore.setState({ drillDown: link, title: 'Critical' })
    renderWith(<DrillDownPanel />)

    await userEvent.click(await screen.findByRole('button', { name: 'Open full list' }))

    expect(navigate).toHaveBeenCalledWith(link)
    // Closed first — navigating with it open leaves focus restoring into a tree
    // the router has already replaced.
    expect(useDrillDownStore.getState().drillDown).toBeNull()
  })

  /**
   * An empty panel under a non-zero figure is a real discrepancy, not a dead
   * end: the summary tables are up to five minutes behind the tickets. Saying
   * so beats leaving somebody to decide the dashboard is broken.
   */
  it('explains an empty result rather than just showing nothing', async () => {
    served([])
    useDrillDownStore.setState({ drillDown: '/tickets?level=CRITICAL', title: 'Critical' })
    renderWith(<DrillDownPanel />)

    expect(await screen.findByText(/computed up to five minutes ago/)).toBeInTheDocument()
  })

  it('says when it is showing only the first page', async () => {
    served(ROWS, { hasMore: true })
    useDrillDownStore.setState({ drillDown: '/tickets?level=CRITICAL', title: 'Critical' })
    renderWith(<DrillDownPanel />)

    expect(await screen.findByText(/Showing the first 25/)).toBeInTheDocument()
  })

  it('does not fetch while closed', () => {
    renderWith(<DrillDownPanel />)

    expect(useListTickets).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ query: expect.objectContaining({ enabled: false }) }),
    )
  })
})
