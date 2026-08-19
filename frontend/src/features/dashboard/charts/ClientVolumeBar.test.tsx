import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ClientVolumeBar } from './ClientVolumeBar'
import { useDrillDownStore } from '../drillDownStore'

/**
 * A-059 · widget 20.
 *
 * Rendered directly rather than through `WidgetFrame`, following A-057's suite:
 * the frame's four states have their own tests, and what matters here is what
 * the chart does with the series it is handed — above all the pooled bar, which
 * is the one segment on the dashboard that deliberately does not link.
 */

const wrap = (ui: React.ReactNode) => render(<MemoryRouter>{ui}</MemoryRouter>)

beforeEach(() => {
  vi.clearAllMocks()
  useDrillDownStore.setState({ drillDown: null, title: '' })
})

const series = [
  {
    name: 'Tickets raised',
    points: [
      {
        x: 'Acme Industries',
        y: 42,
        drillDown: '/tickets?clientId=1&reportedFrom=2026-08-01&reportedTo=2026-08-31',
      },
      {
        x: 'Globex',
        y: 17,
        drillDown: '/tickets?clientId=2&reportedFrom=2026-08-01&reportedTo=2026-08-31',
      },
      { x: 'Other (5 clients)', y: 9, drillDown: null },
    ],
  },
]

describe('ClientVolumeBar', () => {
  /**
   * The bars are `<path>` elements inside an aria-hidden drawing, so the legend
   * is the only keyboard route to the same destinations — §S-05 asks that every
   * chart segment deep-links, not every one a mouse can reach.
   */
  it('reaches every client through the legend', async () => {
    wrap(<ClientVolumeBar series={series} />)

    const legend = screen.getByRole('list', { name: /clients/i })

    await userEvent.click(within(legend).getByRole('button', { name: /^Acme Industries: 42\./ }))

    expect(useDrillDownStore.getState().drillDown).toBe(
      '/tickets?clientId=1&reportedFrom=2026-08-01&reportedTo=2026-08-31',
    )
  })

  /**
   * The pooled bar has no filter that expresses it, so `ChartLegend` renders it
   * as text rather than as a control. A disabled button would still take a tab
   * stop and still announce itself as something that can be pressed.
   */
  it('offers no control for the pooled remainder', () => {
    wrap(<ClientVolumeBar series={series} />)

    const legend = screen.getByRole('list', { name: /clients/i })

    expect(within(legend).getAllByRole('button')).toHaveLength(2)
    expect(within(legend).getByText(/Other \(5 clients\)/)).toBeInTheDocument()
  })

  /**
   * The pooled bar behaves differently from every other bar — it does not open
   * anything — so it must not look like the next client in the ranking. A
   * palette colour would make its silence on click read as a broken link.
   */
  it('draws the pooled bar in a muted token, not the next palette colour', () => {
    wrap(<ClientVolumeBar series={series} />)

    // Asserted through the legend swatches rather than the bars: recharts
    // measures its container, and jsdom reports every element as zero by zero,
    // so the drawing itself renders nothing here. The swatches are fed by the
    // same `colourFor`, so they are evidence of the same decision — and they
    // are the half a screen reader and a keyboard actually reach.
    const swatches = [...screen.getByRole('list', { name: /clients/i }).querySelectorAll<HTMLElement>('span[aria-hidden="true"]')]
      .map((s) => s.style.backgroundColor)

    expect(swatches).toHaveLength(3)
    expect(swatches[0]).toBe('var(--chart-1)')
    expect(swatches[1]).toBe('var(--chart-2)')
    expect(swatches[2]).toBe('var(--text-secondary)')
  })

  /**
   * Order is the information here — the question the widget answers is who
   * raises the most — so the server's ranking is drawn as sent. `PriorityBar`
   * refuses to sort by value for the opposite and equally deliberate reason.
   */
  it('keeps the server’s order, largest first', () => {
    wrap(<ClientVolumeBar series={series} />)

    const legend = screen.getByRole('list', { name: /clients/i })
    const labels = within(legend)
      .getAllByRole('listitem')
      .map((li) => li.textContent)

    expect(labels[0]).toMatch(/^Acme Industries/)
    expect(labels[1]).toMatch(/^Globex/)
    expect(labels[2]).toMatch(/^Other/)
  })

  it('survives a widget with no clients at all', () => {
    wrap(<ClientVolumeBar series={[{ name: 'Tickets raised', points: [] }]} />)

    expect(screen.getByRole('list', { name: /clients/i })).toBeEmptyDOMElement()
  })
})
