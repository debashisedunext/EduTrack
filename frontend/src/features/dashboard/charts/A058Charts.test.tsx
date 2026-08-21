import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { HandoffLatencyLine } from './HandoffLatencyLine'
import { ReworkPanel } from './ReworkPanel'
import { StageDurationBar } from './StageDurationBar'
import { StageFunnel } from './StageFunnel'
import { useDrillDownStore } from '../drillDownStore'

/**
 * A-058 · widgets 16–19.
 *
 * Rendered directly rather than through `WidgetFrame`, following A-057's and
 * A-059's suites: the frame's four states have their own tests, and what
 * matters here is what each chart does with the series it is handed.
 *
 * Three of these four have a property that would survive review while being
 * wrong, and those are what is pinned:
 *
 * - the funnel's **order**, which is the entire content of a funnel and which
 *   any value sort would destroy while leaving the numbers correct;
 * - the rework panel's **nesting**, where stacking the three figures the server
 *   sends would count every ping-pong ticket twice;
 * - which segments **do not link**, since a plausible wrong link is A-060's
 *   defect and is invisible until somebody counts rows.
 */

const wrap = (ui: React.ReactNode) => render(<MemoryRouter>{ui}</MemoryRouter>)

beforeEach(() => {
  vi.clearAllMocks()
  useDrillDownStore.setState({ drillDown: null, title: '' })
})

describe('StageFunnel', () => {
  const series = [
    {
      name: 'Tickets in stage',
      points: [
        { x: 'Triage', y: 2, drillDown: '/tickets?stage=TRIAGE&excludeClosed=true' },
        { x: 'Development', y: 0, drillDown: '/tickets?stage=DEV&excludeClosed=true' },
        { x: 'QA', y: 9, drillDown: '/tickets?stage=QA&excludeClosed=true' },
      ],
    },
  ]

  /**
   * The whole widget. §4A.8 is "spot the bottleneck instantly", and a
   * bottleneck is a bulge at a known point in a known sequence — sorted by
   * value this legend would read QA, Triage, Development, which is the same
   * three numbers arranged so the bulge cannot be located.
   */
  it('keeps the ribbon order rather than sorting by size', () => {
    wrap(<StageFunnel series={series} />)

    const legend = screen.getByRole('list', { name: /stages/i })
    // The accessible name rather than `textContent`: `ChartLegend` renders the
    // label and its figure as adjacent nodes, so textContent reads "Triage2" —
    // and a test asserting *that* would be pinning the markup rather than the
    // order, and would break the next time a separator changed.
    const labels = within(legend)
      .getAllByRole('button')
      .map((button) => button.getAttribute('aria-label')?.replace(/:.*/, ''))

    expect(labels).toEqual(['Triage', 'Development', 'QA'])
  })

  /**
   * Unlike the donut, which omits a task type with nothing open. A gap in the
   * middle of a funnel says work is arriving after that stage and not sitting
   * in it; omitting the band makes two non-adjacent stages look adjacent.
   */
  it('draws a stage nothing is sitting in', () => {
    wrap(<StageFunnel series={series} />)

    const legend = screen.getByRole('list', { name: /stages/i })
    expect(within(legend).getByRole('button', { name: /^Development: 0\./ })).toBeInTheDocument()
  })

  it('opens the tickets standing in a stage, using the stage code the list filters on', async () => {
    wrap(<StageFunnel series={series} />)

    const legend = screen.getByRole('list', { name: /stages/i })
    await userEvent.click(within(legend).getByRole('button', { name: /^QA: 9\./ }))

    expect(useDrillDownStore.getState().drillDown).toBe('/tickets?stage=QA&excludeClosed=true')
  })
})

describe('ReworkPanel', () => {
  const series = [
    {
      name: 'Open tickets by rework',
      points: [
        { x: 'Reworked (2 or more passes)', y: 12, drillDown: null },
        { x: 'Ping-pong (3 or more passes)', y: 3, drillDown: null },
        { x: 'First pass', y: 68, drillDown: null },
      ],
    },
  ]

  /**
   * 🔴 The figure that makes the widget readable at all. Twelve is a crisis in
   * a team of twenty tickets and a rounding error in two thousand, and the
   * server sends the remainder precisely so the denominator can be stated
   * without the client inventing one.
   */
  it('states the rework count against the open backlog it came from', () => {
    wrap(<ReworkPanel series={series} />)

    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText(/of 80 open tickets sent back at least once/)).toBeInTheDocument()
  })

  /**
   * 🔴 The trap the server's own comment names. `Reworked` is iteration >= 2 and
   * `Ping-pong` is iteration >= 3, so every ping-pong ticket is already inside
   * the rework figure. Adding all three would make 83 out of a backlog of 80.
   */
  it('nests ping-pong inside rework rather than adding it to the backlog', () => {
    wrap(<ReworkPanel series={series} />)

    // 12 + 68, never 12 + 3 + 68.
    expect(screen.getByText(/of 80 open/)).toBeInTheDocument()
    expect(screen.queryByText(/of 83 open/)).not.toBeInTheDocument()
    expect(screen.getByText(/of those are on a third pass or beyond/)).toBeInTheDocument()
  })

  /**
   * The share is stated in words as well as drawn, because the bar is the one
   * place on this screen where colour alone separates two quantities and
   * CLAUDE.md makes colour never the only signal.
   */
  it('states the share in words, not only as a coloured bar', () => {
    wrap(<ReworkPanel series={series} />)

    expect(screen.getByText(/15% of open work has been reworked at least once/))
      .toBeInTheDocument()
  })

  /**
   * A summary row of zeroes would divide by nothing. NaN reaches the DOM as a
   * width of "NaN%", which renders as zero — a clean-looking chart built on a
   * broken sum.
   */
  it('survives an empty backlog without dividing by zero', () => {
    wrap(
      <ReworkPanel
        series={[
          {
            name: 'Open tickets by rework',
            points: [
              { x: 'Reworked (2 or more passes)', y: 0, drillDown: null },
              { x: 'Ping-pong (3 or more passes)', y: 0, drillDown: null },
              { x: 'First pass', y: 0, drillDown: null },
            ],
          },
        ]}
      />,
    )

    expect(screen.getByText(/0% of open work/)).toBeInTheDocument()
  })
})

describe('StageDurationBar', () => {
  const series = [
    {
      name: 'Active',
      points: [
        { x: 'Development', y: 1.5, drillDown: '/tickets?stage=DEV&excludeClosed=true' },
        { x: 'QA', y: 0.25, drillDown: '/tickets?stage=QA&excludeClosed=true' },
      ],
    },
    {
      name: 'Idle',
      points: [
        { x: 'Development', y: 6, drillDown: '/tickets?stage=DEV&excludeClosed=true' },
        { x: 'QA', y: 30, drillDown: '/tickets?stage=QA&excludeClosed=true' },
      ],
    },
  ]

  it('names both halves of the split, since the split is the widget', () => {
    wrap(<StageDurationBar series={series} />)

    const legend = screen.getByRole('list', { name: /worked and time waiting/i })
    expect(within(legend).getByText('Active')).toBeInTheDocument()
    expect(within(legend).getByText('Idle')).toBeInTheDocument()
  })

  /**
   * "Idle" is a measurement of a stage, not a set of tickets — there is no list
   * of idle tickets for a link to open. `ChartLegend` renders an entry with no
   * target as text, so it does not take a tab stop or announce itself as
   * something that can be pressed.
   */
  it('offers no control for either measure, since neither names a list', () => {
    wrap(<StageDurationBar series={series} />)

    const legend = screen.getByRole('list', { name: /worked and time waiting/i })
    expect(within(legend).queryAllByRole('button')).toHaveLength(0)
  })
})

describe('HandoffLatencyLine', () => {
  const series = [
    {
      name: 'Average handoff wait (hours)',
      points: [
        { x: '2026-08-10', y: 2, drillDown: null },
        // 2026-08-11 absent on purpose: nothing was handed over, and a zero
        // would claim handoffs were instantaneous that day.
        { x: '2026-08-12', y: 0.5, drillDown: null },
      ],
    },
  ]

  it('names the measure so a reader knows the axis is hours of waiting', () => {
    wrap(<HandoffLatencyLine series={series} />)

    const legend = screen.getByRole('list', { name: /waited between stages/i })
    expect(within(legend).getByText('Average handoff wait (hours)')).toBeInTheDocument()
  })

  /**
   * §7.9 gives this widget's drill-down as "slowest handoffs" — a list of hops,
   * which `GET /tickets` cannot express. The nearest parameter filters on when
   * a ticket was raised and would open a plausible, different set: A-060's
   * defect exactly.
   */
  it('offers no control, because no ticket filter expresses a handoff', () => {
    wrap(<HandoffLatencyLine series={series} />)

    const legend = screen.getByRole('list', { name: /waited between stages/i })
    expect(within(legend).queryAllByRole('button')).toHaveLength(0)
  })
})
