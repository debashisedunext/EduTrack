import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import type { Cycle } from '@/api/generated/model/cycle'
import type { EffortLog } from '@/api/generated/model/effortLog'
import { EffortTab } from './EffortTab'

/**
 * C-061 · the Effort tab, blueprint §7: "every log line: date, resource,
 * hours, description, cycle no. Sum per cycle + grand total."
 *
 * Driven entirely by props, the same choice `AttachmentsTab.test.tsx` and
 * `HistoryTab`'s own tests make: this pins the grouping and the totals this
 * component adds, without a mock server in the way.
 */

let nextId = 1

function entry(overrides: Partial<EffortLog> = {}): EffortLog {
  return {
    id: nextId++,
    user: { id: 3, displayName: 'Ravi Kumar' },
    hours: 2,
    workDate: '2026-08-03',
    note: 'Reproduced and patched the token refresh path.',
    stageCode: 'DEVELOPMENT',
    cycleNo: 1,
    iterationNo: 1,
    isCorrection: false,
    correctsEntryId: null,
    createdAt: '2026-08-03T12:00:00Z',
    ...overrides,
  } as EffortLog
}

function renderTab(entries: EffortLog[], overrides: Partial<ComponentProps<typeof EffortTab>> = {}) {
  const onLoadMore = vi.fn()
  const utils = render(
    <EffortTab
      entries={entries}
      cycles={undefined}
      grandTotalHrs={0}
      isLoading={false}
      loadError={null}
      hasMore={false}
      isLoadingMore={false}
      onLoadMore={onLoadMore}
      {...overrides}
    />,
  )
  return { ...utils, onLoadMore }
}

const cycleButton = (cycleNo: number) => screen.getByRole('button', { name: new RegExp(`Cycle ${cycleNo}\\b`) })

describe('EffortTab — grouped by cycle', () => {
  it('opens only the most recent cycle by default', () => {
    renderTab([entry({ cycleNo: 1 }), entry({ cycleNo: 2 })])

    expect(cycleButton(2)).toHaveAttribute('aria-expanded', 'true')
    expect(cycleButton(1)).toHaveAttribute('aria-expanded', 'false')
  })

  it('expands an earlier cycle on request', () => {
    renderTab([entry({ cycleNo: 1 }), entry({ cycleNo: 2 })])

    fireEvent.click(cycleButton(1))
    expect(cycleButton(1)).toHaveAttribute('aria-expanded', 'true')
  })

  it('buckets a row with no cycle stamped under "Before cycle 1" rather than dropping it', () => {
    renderTab([entry({ cycleNo: undefined })])
    expect(screen.getByRole('button', { name: /Before cycle 1/ })).toBeInTheDocument()
  })

  it('orders rows within a cycle chronologically, oldest first', () => {
    renderTab([
      entry({ cycleNo: 1, note: 'second', createdAt: '2026-08-05T00:00:00Z' }),
      entry({ cycleNo: 1, note: 'first', createdAt: '2026-08-03T00:00:00Z' }),
    ])

    const cells = screen.getAllByRole('cell').map((c) => c.textContent)
    expect(cells.indexOf('first')).toBeLessThan(cells.indexOf('second'))
  })
})

describe('EffortTab — totals', () => {
  const CYCLES: Cycle[] = [
    { cycleNo: 1, totalEffortHrs: 24.5 },
    { cycleNo: 2, totalEffortHrs: 13.5 },
  ]

  it('shows the grand total at the top, from the prop rather than summed from the rows', () => {
    // Only one row loaded (as if "Load more" has not been pressed), but the
    // grand total still reads the full, materialised figure.
    renderTab([entry({ cycleNo: 1, hours: 2 })], { cycles: CYCLES, grandTotalHrs: 38 })
    expect(screen.getByText(/Grand total/)).toHaveTextContent('38.0 h')
  })

  it("labels each cycle's group header with that cycle's materialised total, not a sum of the rows shown", () => {
    renderTab([entry({ cycleNo: 1, hours: 2 }), entry({ cycleNo: 2, hours: 3 })], { cycles: CYCLES, grandTotalHrs: 38 })

    expect(cycleButton(1)).toHaveTextContent('24.5 h')
    expect(cycleButton(2)).toHaveTextContent('13.5 h')
  })

  it('falls back to 0 h for a cycle the materialised totals do not carry', () => {
    renderTab([entry({ cycleNo: 3 })], { cycles: CYCLES, grandTotalHrs: 0 })
    expect(cycleButton(3)).toHaveTextContent('0.0 h')
  })
})

describe('EffortTab — one log line: date, resource, hours, description', () => {
  it('renders every field blueprint §7 asks for', () => {
    renderTab([
      entry({
        workDate: '2026-08-05',
        user: { id: 4, displayName: 'Anil Shah' },
        hours: 3.5,
        note: 'Found three defects on the checkout path.',
      }),
    ])

    const row = screen.getByRole('row', { name: /Anil Shah/ })
    expect(within(row).getByText('5 Aug 2026')).toBeInTheDocument()
    expect(within(row).getByText('Anil Shah')).toBeInTheDocument()
    expect(within(row).getByText('3.5 h')).toBeInTheDocument()
    expect(within(row).getByText('Found three defects on the checkout path.')).toBeInTheDocument()
  })

  it('marks a correction row rather than letting a negative figure read as a mistake', () => {
    renderTab([entry({ hours: -1, isCorrection: true, note: 'Reversed: logged against the wrong stage.' })])
    expect(screen.getByText('(correction)')).toBeInTheDocument()
    expect(screen.getByText('-1.0 h')).toBeInTheDocument()
  })

  it('shows a dash rather than nothing when a line has no description', () => {
    renderTab([entry({ note: null })])
    expect(screen.getByText('—')).toBeInTheDocument()
  })
})

describe('EffortTab — loading, error and empty states', () => {
  it('shows a skeleton while loading, not the empty state', () => {
    renderTab([], { isLoading: true })
    expect(screen.queryByText('No effort logged yet')).not.toBeInTheDocument()
  })

  it('surfaces the load error', () => {
    renderTab([], { loadError: 'This ticket’s effort log could not be loaded.' })
    expect(screen.getByRole('alert')).toHaveTextContent('could not be loaded')
  })

  it('says plainly when nothing has been logged', () => {
    renderTab([])
    expect(screen.getByText('No effort logged yet')).toBeInTheDocument()
  })
})

describe('EffortTab — C-052 the ribbon filter', () => {
  it('narrows the rows shown to the filtered stage, iteration and cycle', () => {
    renderTab(
      [
        entry({ stageCode: 'DEVELOPMENT', iterationNo: 1, cycleNo: 1, note: 'first pass' }),
        entry({ stageCode: 'QA', iterationNo: 1, cycleNo: 1, note: 'testing' }),
        entry({ stageCode: 'DEVELOPMENT', iterationNo: 2, cycleNo: 1, note: 'rework' }),
      ],
      { filter: { stageCode: 'DEVELOPMENT', iterationNo: 1, cycleNo: 1, displayName: 'Development' } },
    )

    expect(screen.getByText('first pass')).toBeInTheDocument()
    expect(screen.queryByText('testing')).not.toBeInTheDocument()
    expect(screen.queryByText('rework')).not.toBeInTheDocument()
  })

  it('leaves the grand total reading the whole ticket, not the filtered rows', () => {
    renderTab([entry({ stageCode: 'DEVELOPMENT', iterationNo: 1, cycleNo: 1 })], {
      grandTotalHrs: 38,
      filter: { stageCode: 'QA', iterationNo: 1, cycleNo: 1, displayName: 'QA' },
    })
    expect(screen.getByText(/Grand total/)).toHaveTextContent('38.0 h')
  })

  it('names the filtered stage in the chip and the empty state', () => {
    renderTab([entry({ stageCode: 'QA' })], {
      filter: { stageCode: 'DEVELOPMENT', iterationNo: 1, cycleNo: 1, displayName: 'Development' },
    })
    expect(screen.getByText('Filtered to Development')).toBeInTheDocument()
    expect(screen.getByText('No effort logged for Development')).toBeInTheDocument()
  })

  it('names the iteration in the chip once the stage has looped', () => {
    renderTab([entry({ stageCode: 'DEVELOPMENT', iterationNo: 2 })], {
      filter: { stageCode: 'DEVELOPMENT', iterationNo: 2, cycleNo: 1, displayName: 'Development' },
    })
    expect(screen.getByText('Filtered to Development · Iteration 2')).toBeInTheDocument()
  })

  it('reports a clear-filter click back to its caller', () => {
    const onClearFilter = vi.fn()
    renderTab([entry({ stageCode: 'QA' })], {
      filter: { stageCode: 'DEVELOPMENT', iterationNo: 1, cycleNo: 1, displayName: 'Development' },
      onClearFilter,
    })

    fireEvent.click(screen.getByRole('button', { name: /Clear filter: Development/ }))
    expect(onClearFilter).toHaveBeenCalled()
  })
})

describe('EffortTab — "Load more"', () => {
  it('is withheld until the server says there is more', () => {
    renderTab([entry()], { hasMore: false })
    expect(screen.queryByRole('button', { name: /Load more/ })).not.toBeInTheDocument()
  })

  it('reports a click back to its caller', () => {
    const { onLoadMore } = renderTab([entry()], { hasMore: true })
    fireEvent.click(screen.getByRole('button', { name: /Load more/ }))
    expect(onLoadMore).toHaveBeenCalled()
  })
})
