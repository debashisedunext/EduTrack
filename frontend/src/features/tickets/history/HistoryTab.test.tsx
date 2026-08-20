import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { HistoryEntry } from '@/api/generated/model/historyEntry'
import { HistoryTab } from './HistoryTab'

/**
 * C-052 · the ribbon filter over the History tab. `TicketHistory.test.tsx`
 * already proves grouping, the comments toggle and the append-only guarantee
 * against the mock server; this is driven entirely by props, the same choice
 * `EffortTab.test.tsx` makes, to pin the filtering this task adds without a
 * server in the way.
 */

let nextId = 1

function entry(overrides: Partial<HistoryEntry> = {}): HistoryEntry {
  return {
    id: nextId++,
    action: 'REOPENED',
    note: 'default entry',
    stageCode: 'DEVELOPMENT',
    cycleNo: 1,
    iterationNo: 1,
    isCorrection: false,
    createdAt: '2026-08-03T12:00:00Z',
    ...overrides,
  } as HistoryEntry
}

function renderTab(entries: HistoryEntry[], overrides: Partial<ComponentProps<typeof HistoryTab>> = {}) {
  const onIncludeCommentsChange = vi.fn()
  const onLoadMore = vi.fn()
  return render(
    <HistoryTab
      entries={entries}
      isLoading={false}
      loadError={null}
      includeComments={false}
      onIncludeCommentsChange={onIncludeCommentsChange}
      hasMore={false}
      isLoadingMore={false}
      onLoadMore={onLoadMore}
      {...overrides}
    />,
  )
}

describe('HistoryTab — C-052 the ribbon filter', () => {
  it('narrows entries to the filtered stage, iteration and cycle', () => {
    renderTab(
      [
        entry({ stageCode: 'DEVELOPMENT', iterationNo: 1, cycleNo: 1, note: 'first pass' }),
        entry({ stageCode: 'QA', iterationNo: 1, cycleNo: 1, note: 'testing' }),
        entry({ stageCode: 'DEVELOPMENT', iterationNo: 2, cycleNo: 1, note: 'rework' }),
      ],
      { filter: { stageCode: 'DEVELOPMENT', iterationNo: 1, cycleNo: 1, displayName: 'Development' } },
    )

    expect(screen.getByText(/first pass/)).toBeInTheDocument()
    expect(screen.queryByText(/testing/)).not.toBeInTheDocument()
    expect(screen.queryByText(/rework/)).not.toBeInTheDocument()
  })

  // The trap `segmentFilter.ts` names: a reopened ticket can read the same
  // stage and iteration in two different cycles.
  it('does not fold a later cycle’s entries into an earlier cycle’s filter', () => {
    renderTab(
      [
        entry({ stageCode: 'DEVELOPMENT', iterationNo: 1, cycleNo: 1, note: 'cycle one' }),
        entry({ stageCode: 'DEVELOPMENT', iterationNo: 1, cycleNo: 2, note: 'cycle two' }),
      ],
      { filter: { stageCode: 'DEVELOPMENT', iterationNo: 1, cycleNo: 1, displayName: 'Development' } },
    )

    expect(screen.getByText(/cycle one/)).toBeInTheDocument()
    expect(screen.queryByText(/cycle two/)).not.toBeInTheDocument()
  })

  it('names the filtered stage in the chip and the empty state', () => {
    renderTab([entry({ stageCode: 'QA' })], {
      filter: { stageCode: 'DEVELOPMENT', iterationNo: 1, cycleNo: 1, displayName: 'Development' },
    })
    expect(screen.getByText('Filtered to Development')).toBeInTheDocument()
    expect(screen.getByText('No history for Development')).toBeInTheDocument()
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

  it('renders no chip and every entry when no filter is given', () => {
    renderTab([entry({ stageCode: 'DEVELOPMENT' }), entry({ stageCode: 'QA' })])
    expect(screen.queryByText(/^Filtered to/)).not.toBeInTheDocument()
  })
})
