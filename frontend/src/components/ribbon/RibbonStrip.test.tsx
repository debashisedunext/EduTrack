import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

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

  // No `onSelectSegment` given at all is still read-only — a sealed cycle's
  // strip, or any caller that has not wired selection, must not offer a
  // control that does nothing.
  it('renders read-only tiles when the caller wires no onSelectSegment', () => {
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

describe('RibbonStrip · C-052 selection', () => {
  it('makes every tile a button and forwards the clicked segment', async () => {
    const onSelectSegment = vi.fn()
    const intake = seg({ stageCode: 'INTAKE', displayName: 'Intake' })
    render(<RibbonStrip ribbon={ribbon([intake])} onSelectSegment={onSelectSegment} />)

    await userEvent.click(screen.getByRole('button'))
    expect(onSelectSegment).toHaveBeenCalledWith(intake)
  })

  it('marks the segment matching selectedSegment as selected, and no other', () => {
    render(
      <RibbonStrip
        ribbon={ribbon([
          seg({ stageCode: 'INTAKE', displayName: 'Intake', iterationNo: 1 }),
          seg({ stageCode: 'TRIAGE', displayName: 'Triage', iterationNo: 1 }),
        ])}
        selectedSegment={{ stageCode: 'TRIAGE', iterationNo: 1 }}
        onSelectSegment={() => {}}
      />,
    )

    const buttons = screen.getAllByRole('button')
    expect(buttons[0]).toHaveAttribute('aria-pressed', 'false')
    expect(buttons[1]).toHaveAttribute('aria-pressed', 'true')
  })

  // A stage can recur across iterations after a loop-back — matching on stage
  // code alone would select the wrong iteration's tile.
  it('does not select a same-stage tile from a different iteration', () => {
    render(
      <RibbonStrip
        ribbon={ribbon([seg({ stageCode: 'DEVELOPMENT', iterationNo: 2 })])}
        selectedSegment={{ stageCode: 'DEVELOPMENT', iterationNo: 1 }}
        onSelectSegment={() => {}}
      />,
    )

    expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'false')
  })

  it('selects even on a sealed cycle — filtering a past cycle is still useful', () => {
    render(
      <RibbonStrip
        ribbon={{ cycleNo: 1, iterationNo: 1, isSealed: true, canAdvance: false, segments: [seg()] }}
        onSelectSegment={() => {}}
      />,
    )
    expect(screen.getByRole('button')).toBeInTheDocument()
  })
})

describe('RibbonStrip · C-052 the contextual action', () => {
  it('renders an honest, disabled handoff placeholder on the current segment when the caller can advance', () => {
    render(
      <RibbonStrip
        ribbon={{
          cycleNo: 1,
          iterationNo: 1,
          isSealed: false,
          canAdvance: true,
          segments: [
            seg({ stageCode: 'DEVELOPMENT', displayName: 'Development', state: SegmentState.CURRENT }),
            seg({ stageCode: 'QA', displayName: 'QA', state: SegmentState.PENDING }),
          ],
        }}
      />,
    )

    const action = screen.getByRole('button', { name: 'Hand off to QA →' })
    expect(action).toBeDisabled()
  })

  it('names the plain verb when the current segment is the last one', () => {
    render(
      <RibbonStrip
        ribbon={{
          cycleNo: 1,
          iterationNo: 1,
          isSealed: false,
          canAdvance: true,
          segments: [seg({ stageCode: 'SIGNOFF', displayName: 'Sign-off', state: SegmentState.CURRENT })],
        }}
      />,
    )

    expect(screen.getByRole('button', { name: 'Hand off →' })).toBeInTheDocument()
  })

  it('renders no action when the caller may not advance — hidden for everyone else', () => {
    render(
      <RibbonStrip
        ribbon={{
          cycleNo: 1,
          iterationNo: 1,
          isSealed: false,
          canAdvance: false,
          segments: [seg({ state: SegmentState.CURRENT })],
        }}
      />,
    )

    expect(screen.queryByRole('button', { name: /Hand off/ })).not.toBeInTheDocument()
  })

  it('renders no action when nothing is current, even if canAdvance is true', () => {
    render(
      <RibbonStrip
        ribbon={{
          cycleNo: 1,
          iterationNo: 1,
          isSealed: true,
          canAdvance: true,
          segments: [seg({ state: SegmentState.COMPLETED })],
        }}
      />,
    )

    expect(screen.queryByRole('button', { name: /Hand off/ })).not.toBeInTheDocument()
  })
})
