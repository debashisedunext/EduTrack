import { beforeAll, describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { KpiCard } from './KpiCard'
import { Sparkline } from './Sparkline'

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  // jsdom has no matchMedia, and useCountUp asks it about reduced motion.
  // Answering "not reduced" exercises the animated path, which is the one worth
  // testing — the reduced path is a single early return.
  window.matchMedia ??= ((query: string) =>
    ({
      matches: false,
      media: query,
      addEventListener() {},
      removeEventListener() {},
    }) as unknown as MediaQueryList) as typeof window.matchMedia
})

function renderCard(props: Partial<React.ComponentProps<typeof KpiCard>> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <KpiCard label="Critical" value={18} drillDown="/tickets?level=CRITICAL" {...props} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('the KPI card', () => {
  /**
   * §S-05: "every card and every chart segment is clickable and deep-links to a
   * pre-filtered ticket list". Asserted as a link rather than a click handler,
   * because the three things a clickable div takes away — keyboard reach, the
   * announced role, and open-in-new-tab — are invisible in a screenshot.
   */
  it('is a link to the server-built drill-down', async () => {
    renderCard()
    const link = await screen.findByRole('link', { name: /Critical: 18/ })
    expect(link).toHaveAttribute('href', '/tickets?level=CRITICAL')
  })

  /**
   * The accessible name has to carry the number. Six cards whose names are all
   * just their label leave a screen-reader user tabbing a row of "Critical,
   * link" with no values at all.
   */
  it('announces the value, not only the label', async () => {
    renderCard({ label: 'Delayed', value: 26 })
    expect(await screen.findByRole('link', { name: /Delayed: 26/ })).toBeInTheDocument()
  })

  it('counts up to exactly the target value', async () => {
    renderCard({ value: 1284 })
    // Landing a unit short is the failure mode worth pinning: a card reading
    // 1,283 against a list of 1,284 is a discrepancy nobody reports.
    await waitFor(() => expect(screen.getByText('1,284')).toBeInTheDocument())
  })

  describe('the delta badge', () => {
    it('is absent when there is nothing to compare against', async () => {
      renderCard({ deltaPct: null })
      await screen.findByRole('link')
      expect(screen.queryByText(/percent versus/)).not.toBeInTheDocument()
      expect(screen.queryByText(/%$/)).not.toBeInTheDocument()
    })

    it('states direction in words, not only as a glyph', async () => {
      renderCard({ deltaPct: -12.5 })
      // "▼" alone reads as "black down-pointing triangle", which is noise.
      expect(await screen.findByLabelText(/down 12.5 percent versus the previous period/))
        .toBeInTheDocument()
    })

    it('does not colour a rise as good', async () => {
      // More delayed tickets is a rise and not an improvement. The badge states
      // direction and leaves the judgement to the reader.
      renderCard({ label: 'Delayed', deltaPct: 40, tone: 'delayed' })
      const badge = await screen.findByLabelText(/up 40 percent/)
      expect(badge.className).not.toMatch(/success/)
    })
  })
})

describe('the sparkline', () => {
  /**
   * One point is a dot and zero points is a card with no history. Both render
   * as nothing, because a flat line is a claim that the value did not move.
   */
  it('draws nothing for fewer than two points', () => {
    const { container } = render(<Sparkline points={[5]} label="trend" />)
    expect(container.querySelector('svg')).toBeNull()
  })

  it('draws a path once there is a trend', () => {
    const { container } = render(<Sparkline points={[1, 4, 2, 9]} label="Open trend" />)
    expect(container.querySelector('path')).not.toBeNull()
    expect(screen.getByRole('img', { name: 'Open trend' })).toBeInTheDocument()
  })

  /**
   * A genuinely flat series has zero span and would divide by zero. Drawn along
   * the middle rather than the axis: a week of "12 open" every day is steady,
   * and pinning it to the bottom reads as a collapse.
   */
  it('survives a flat series without dividing by zero', () => {
    const { container } = render(<Sparkline points={[12, 12, 12]} label="flat" />)
    const d = container.querySelector('path')?.getAttribute('d') ?? ''
    expect(d).not.toMatch(/NaN/)
  })
})
