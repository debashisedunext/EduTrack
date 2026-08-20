import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'

import type { Ribbon } from '@/api/generated/model/ribbon'
import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { RibbonStrip } from './RibbonStrip'

function seg(over: Partial<RibbonSegmentData> = {}): RibbonSegmentData {
  return {
    stageCode: 'DEVELOPMENT',
    displayName: 'Development',
    state: SegmentState.COMPLETED,
    sequence: 3,
    owner: { id: 7, displayName: 'Ravi Kumar' },
    ownerRole: 'DEVELOPER',
    enteredAt: '2026-08-01T09:00:00Z',
    exitedAt: '2026-08-03T13:00:00Z',
    durationMins: 2940,
    effortHrs: 14.5,
    iterationNo: 1,
    loopBackCount: 0,
    ...over,
  }
}

function ribbon(segments: RibbonSegmentData[]): Ribbon {
  return { cycleNo: 1, iterationNo: 1, isSealed: false, canAdvance: false, segments }
}

describe('RibbonStrip · C-051', () => {
  it('renders every segment, in order, as a labelled list', () => {
    render(
      <RibbonStrip
        ribbon={ribbon([
          seg({ stageCode: 'INTAKE', displayName: 'Intake', sequence: 1 }),
          seg({ stageCode: 'TRIAGE', displayName: 'Triage', sequence: 2 }),
          seg({ stageCode: 'DEVELOPMENT', displayName: 'Development', sequence: 3 }),
        ])}
      />,
    )

    const list = screen.getByRole('list', { name: 'Workflow stages' })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(items.map((item) => within(item).getByTestId('ribbon-segment'))).toHaveLength(3)
    expect(within(items[0]).getByText('Intake')).toBeInTheDocument()
    expect(within(items[1]).getByText('Triage')).toBeInTheDocument()
    expect(within(items[2]).getByText('Development')).toBeInTheDocument()
  })

  it('marks only the last segment as last, so every connector but the last one draws', () => {
    render(
      <RibbonStrip
        ribbon={ribbon([
          seg({ stageCode: 'INTAKE', displayName: 'Intake' }),
          seg({ stageCode: 'TRIAGE', displayName: 'Triage' }),
        ])}
      />,
    )

    expect(screen.getAllByTestId('ribbon-connector')).toHaveLength(1)
  })

  // C-052 owns interactions. A tile with no `onSelect` renders as a plain,
  // unfocusable group — the strip must not offer a control that does nothing.
  it('renders read-only tiles, since no interaction is wired here yet', () => {
    render(<RibbonStrip ribbon={ribbon([seg()])} />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByRole('listitem')).toBeInTheDocument()
  })

  it('shows an empty state for a ticket with no workflow template, rather than an empty list', () => {
    render(<RibbonStrip ribbon={ribbon([])} />)
    expect(screen.queryByRole('list', { name: 'Workflow stages' })).not.toBeInTheDocument()
    expect(screen.getByText('No workflow ribbon')).toBeInTheDocument()
  })

  it('shows the same empty state when no ribbon was returned at all', () => {
    render(<RibbonStrip ribbon={undefined} />)
    expect(screen.getByText('No workflow ribbon')).toBeInTheDocument()
  })
})
