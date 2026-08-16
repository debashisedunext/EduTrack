import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { WidgetFrame } from './WidgetFrame'
import { PriorityBar } from './charts/PriorityBar'

/**
 * A-056 · the states a widget can be in, and the two that must never be
 * confused.
 *
 * The whole reason `WidgetFrame` is not three lines is that "no data" and "your
 * role has no table for this" render identically if nobody insists otherwise —
 * and the second one told as the first is a chart quietly asserting that
 * somebody has no critical tickets when the truth is that nobody counted.
 */

const navigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigate }
})

const useGetDashboardWidget = vi.fn()
vi.mock('@/api/generated/dashboard/dashboard', () => ({
  useGetDashboardWidget: (...args: unknown[]) => useGetDashboardWidget(...args),
}))

function renderFrame() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <WidgetFrame
          widgetKey="priority-bar"
          title="Priority split"
          categoryLabel="Priority"
          params={{}}
        >
          {(series) => <PriorityBar series={series} />}
        </WidgetFrame>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const SERIES = [
  {
    name: 'Open by priority',
    points: [
      { x: 'Low', y: 4, drillDown: '/tickets?level=LOW&excludeClosed=true' },
      { x: 'Medium', y: 3, drillDown: '/tickets?level=MEDIUM&excludeClosed=true' },
      { x: 'High', y: 2, drillDown: '/tickets?level=HIGH&excludeClosed=true' },
      { x: 'Critical', y: 1, drillDown: '/tickets?level=CRITICAL&excludeClosed=true' },
    ],
  },
]

function served(data: Record<string, unknown>) {
  useGetDashboardWidget.mockReturnValue({ data: { data }, isPending: false, isError: false })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('WidgetFrame', () => {
  it('shows a skeleton the size of the chart while the first request is in flight', () => {
    useGetDashboardWidget.mockReturnValue({ data: undefined, isPending: true, isError: false })
    renderFrame()

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Priority split' })).toBeInTheDocument()
  })

  it('reports a failed request without claiming there is no data', async () => {
    useGetDashboardWidget.mockReturnValue({ data: undefined, isPending: false, isError: true })
    renderFrame()

    expect(await screen.findByRole('status')).toHaveTextContent(/could not be loaded/i)
    expect(screen.queryByText(/nothing to show/i)).not.toBeInTheDocument()
  })

  /**
   * The distinction this component exists for. The server says the caller's
   * role has no table that can answer this; rendering an empty chart instead
   * would state, falsely, that nothing matched.
   */
  it('shows the server reason verbatim when a role cannot be answered, not an empty chart', async () => {
    served({
      key: 'priority-bar',
      unavailableReason: 'This breakdown is not kept per resource.',
      series: [],
    })
    renderFrame()

    expect(await screen.findByRole('status')).toHaveTextContent(
      'This breakdown is not kept per resource.',
    )
    expect(screen.queryByText(/nothing to show/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('says "nothing to show" only when the role could see it and there is genuinely nothing', async () => {
    served({ key: 'priority-bar', series: [{ name: 'Open by priority', points: [] }] })
    renderFrame()

    expect(await screen.findByRole('status')).toHaveTextContent(/nothing to show/i)
  })

  /**
   * Recharts draws paths with no text alternative. CLAUDE.md makes
   * accessibility non-optional and §12.2 asks for it on charts specifically, so
   * every figure is also a table cell.
   */
  it('renders every figure as a table so the chart is readable without seeing it', () => {
    served({ key: 'priority-bar', series: SERIES })
    renderFrame()

    const table = screen.getByRole('table')
    expect(within(table).getByRole('columnheader', { name: 'Priority' })).toBeInTheDocument()
    expect(within(table).getByRole('rowheader', { name: 'Critical' })).toBeInTheDocument()

    const criticalRow = within(table).getByRole('rowheader', { name: 'Critical' }).closest('tr')!
    expect(within(criticalRow).getByRole('cell')).toHaveTextContent('1')
  })

  /**
   * §S-05: every chart segment deep-links. The drawing is aria-hidden and
   * unfocusable, so the legend is the only route for a keyboard — and it has to
   * actually work.
   */
  it('deep-links from the legend, using the server-built target', async () => {
    served({ key: 'priority-bar', series: SERIES })
    renderFrame()

    await userEvent.click(screen.getByRole('button', { name: /^Critical: 1\./ }))

    expect(navigate).toHaveBeenCalledWith('/tickets?level=CRITICAL&excludeClosed=true')
  })

  it('names the value in the legend, so four buttons are not four identical labels', () => {
    served({ key: 'priority-bar', series: SERIES })
    renderFrame()

    expect(
      screen.getByRole('button', { name: 'Low: 4. Open the filtered ticket list.' }),
    ).toBeInTheDocument()
  })

  /**
   * A segment with nowhere to go is not a control. A disabled button still
   * takes a tab stop and still announces itself, which is worse than a label
   * that was never interactive — the aging buckets are the real case.
   */
  it('renders a segment with no target as text rather than an unpressable button', () => {
    served({
      key: 'priority-bar',
      series: [{ name: 'Open by priority', points: [{ x: 'Low', y: 4, drillDown: null }] }],
    })
    renderFrame()

    // Scoped to the legend: "Low" also appears as a row header in the hidden
    // data table, which is the point of that table and not a duplicate to
    // deduplicate away.
    const legend = screen.getByRole('list', { name: /priority levels/i })
    expect(within(legend).queryByRole('button')).not.toBeInTheDocument()
    expect(within(legend).getByText('Low')).toBeInTheDocument()
  })
})
