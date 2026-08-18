import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { ChartLegend } from './ChartLegend'

/**
 * D-064 · every chart's legend hands the panel the figure it printed.
 *
 * Tested here rather than once per chart because all nine legends pass
 * `onSelect={drillDown}` straight through — this component is the single place
 * the contract holds, and a per-chart test would be eight copies of one
 * assertion.
 *
 * It is also the *accessible* path. `WidgetFrame` marks the drawings
 * `aria-hidden` because recharts segments are `<path>` elements — not
 * focusable, not announced — so these buttons are the only way to the panel
 * without a mouse. A count wired into the drawings alone would have been
 * missing for exactly the readers who cannot see the chart.
 */
describe('D-064 · the legend carries its own number to the panel', () => {
  it('passes the entry value alongside the filter and the label', async () => {
    const onSelect = vi.fn()
    render(
      <ChartLegend
        label="Task type"
        entries={[
          { label: 'Internal Bug', drillDown: '/tickets?taskTypeId=3', value: 24, colour: '#000' },
        ]}
        onSelect={onSelect}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: /Internal Bug/ }))

    expect(onSelect).toHaveBeenCalledWith('/tickets?taskTypeId=3', 'Internal Bug', 24)
  })

  it('passes undefined rather than a zero when a segment has no figure', async () => {
    // A segment with no number must not have one invented for it — the panel
    // prints nothing, which is the honest reading. Zero would be a claim.
    const onSelect = vi.fn()
    render(
      <ChartLegend
        label="Task type"
        entries={[{ label: 'Unmeasured', drillDown: '/tickets?x=1', colour: '#000' }]}
        onSelect={onSelect}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: /Unmeasured/ }))

    expect(onSelect).toHaveBeenCalledWith('/tickets?x=1', 'Unmeasured', undefined)
  })
})
