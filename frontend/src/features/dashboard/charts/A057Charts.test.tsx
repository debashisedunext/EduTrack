import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { CalendarHeatmap } from './CalendarHeatmap'
import { ProjectTreemap } from './ProjectTreemap'
import { SlaGauge } from './SlaGauge'

/**
 * A-057 · widgets 13–15.
 *
 * The charts are rendered directly rather than through `WidgetFrame`, because
 * the frame's four states already have their own suite. What is under test here
 * is what each chart does with the series it is handed — in particular the two
 * cases where an empty-looking rendering would be a false statement rather than
 * an absent one.
 */

const navigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigate }
})

const wrap = (ui: React.ReactNode) => render(<MemoryRouter>{ui}</MemoryRouter>)

beforeEach(() => vi.clearAllMocks())

describe('CalendarHeatmap', () => {
  const series = [
    {
      name: 'Tickets created',
      points: [
        { x: '2026-08-10', y: 4, drillDown: '/tickets?from=2026-08-10&to=2026-08-10' },
        { x: '2026-08-11', y: 0, drillDown: '/tickets?from=2026-08-11&to=2026-08-11' },
        { x: '2026-08-14', y: 8, drillDown: '/tickets?from=2026-08-14&to=2026-08-14' },
      ],
    },
  ]

  it('draws one cell per day the server sent, and none for the days it did not', () => {
    const { container } = wrap(<CalendarHeatmap series={series} />)

    // Three points spanning 10–14 August: the 12th and 13th are absent from the
    // data and must not be drawn as empty cells, which would assert two quiet
    // days the server never measured.
    expect(container.querySelectorAll('rect')).toHaveLength(3)
  })

  it('places each day in its ISO week column and weekday row', () => {
    const { container } = wrap(<CalendarHeatmap series={series} />)
    const rects = [...container.querySelectorAll('rect')]

    // 10 Aug 2026 is a Monday, 11th a Tuesday — same week, adjacent rows.
    const [mon, tue, nextMon] = rects
    expect(mon.getAttribute('x')).toBe(tue.getAttribute('x'))
    expect(Number(tue.getAttribute('y'))).toBeGreaterThan(Number(mon.getAttribute('y')))

    // 14 Aug is the Friday of the same week, so still one column along from
    // nothing — same x as the others, further down.
    expect(nextMon.getAttribute('x')).toBe(mon.getAttribute('x'))
  })

  it('distinguishes a zero day from a busy one by opacity, not by a new colour', () => {
    const { container } = wrap(<CalendarHeatmap series={series} />)
    const rects = [...container.querySelectorAll('rect')]

    expect(rects.every((r) => r.getAttribute('fill') === 'var(--primary)')).toBe(true)
    const zero = Number(rects[1].getAttribute('fill-opacity'))
    const busiest = Number(rects[2].getAttribute('fill-opacity'))
    expect(busiest).toBeGreaterThan(zero)
  })

  it('opens the filtered list for the day that was clicked', async () => {
    const { container } = wrap(<CalendarHeatmap series={series} />)

    await userEvent.click(container.querySelectorAll('rect')[2])

    expect(navigate).toHaveBeenCalledWith('/tickets?from=2026-08-14&to=2026-08-14')
  })
})

describe('SlaGauge', () => {
  const gauge = (met: number, breached: number) => [
    { name: 'Met', points: [{ x: 'Met', y: met, drillDown: '/tickets?status=CLOSED' }] },
    {
      name: 'Breached',
      points: [{ x: 'Breached', y: breached, drillDown: '/tickets?isDelayed=true' }],
    },
  ]

  it('shows the percentage and the counts it was computed from', () => {
    wrap(<SlaGauge series={gauge(6, 4)} />)

    expect(screen.getByText('60%')).toBeInTheDocument()
    // 100% off two tickets and 100% off two hundred are the same needle, so the
    // sample size has to be on the screen too.
    expect(screen.getByText('6 of 10 on time')).toBeInTheDocument()
  })

  /**
   * The failure worth guarding: nothing measured must not render as a needle at
   * zero, which reads as total failure rather than as no measurement.
   */
  it('says there is nothing to measure rather than showing 0%', () => {
    wrap(<SlaGauge series={gauge(0, 0)} />)

    expect(screen.getByRole('status')).toHaveTextContent(/no work with a committed date/i)
    expect(screen.queryByText('0%')).not.toBeInTheDocument()
  })

  it('deep-links the breached half to the overdue list', async () => {
    wrap(<SlaGauge series={gauge(6, 4)} />)

    await userEvent.click(screen.getByRole('button', { name: /^Breached: 4\./ }))

    expect(navigate).toHaveBeenCalledWith('/tickets?isDelayed=true')
  })
})

describe('ProjectTreemap', () => {
  const series = [
    {
      name: 'Open by project',
      points: [
        { x: 'Apollo', y: 40, drillDown: '/tickets?projectId=1&excludeClosed=true' },
        { x: 'Borealis', y: 10, drillDown: '/tickets?projectId=2&excludeClosed=true' },
      ],
    },
  ]

  /**
   * The tiles are `<path>` elements inside an aria-hidden drawing, so without
   * the legend every deep-link here would be mouse-only — and §S-05 asks that
   * every chart segment deep-links, not every one a mouse can reach.
   */
  it('reaches every project through the legend, not only through the tiles', async () => {
    wrap(<ProjectTreemap series={series} />)

    const legend = screen.getByRole('list', { name: /projects/i })
    expect(within(legend).getAllByRole('button')).toHaveLength(2)

    await userEvent.click(within(legend).getByRole('button', { name: /^Apollo: 40\./ }))
    expect(navigate).toHaveBeenCalledWith('/tickets?projectId=1&excludeClosed=true')
  })

  it('names each project with its open count in the accessible name', () => {
    wrap(<ProjectTreemap series={series} />)

    expect(
      screen.getByRole('button', { name: 'Borealis: 10. Open the filtered ticket list.' }),
    ).toBeInTheDocument()
  })
})
