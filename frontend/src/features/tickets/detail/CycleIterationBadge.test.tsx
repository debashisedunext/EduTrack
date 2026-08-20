import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'

import type { Ribbon } from '@/api/generated/model/ribbon'
import { CycleIterationBadge } from './CycleIterationBadge'

describe('CycleIterationBadge · C-054', () => {
  it('renders nothing when there is no ribbon', () => {
    const { container } = render(<CycleIterationBadge />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when the ribbon carries no cycle number — nothing to describe', () => {
    const { container } = render(<CycleIterationBadge ribbon={{ segments: [] }} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('reads "Cycle N · Iteration M" from the ribbon, not the ticket', () => {
    render(<CycleIterationBadge ribbon={{ cycleNo: 2, iterationNo: 3, segments: [] }} />)
    expect(screen.getByText('Cycle 2 · Iteration 3')).toBeInTheDocument()
  })

  it('defaults iteration to 1 when the ribbon omits it — a fresh cycle, never yet bounced', () => {
    render(<CycleIterationBadge ribbon={{ cycleNo: 1, segments: [] }} />)
    expect(screen.getByText('Cycle 1 · Iteration 1')).toBeInTheDocument()
  })

  it('shows no rework count when nothing has bounced', () => {
    render(<CycleIterationBadge ribbon={{ cycleNo: 1, iterationNo: 1, segments: [{ loopBackCount: 0 }] }} />)
    expect(screen.queryByText(/rework/)).not.toBeInTheDocument()
  })

  it('sums every segment\'s loopBackCount for the total rework count', () => {
    const ribbon: Ribbon = {
      cycleNo: 1,
      iterationNo: 3,
      segments: [{ stageCode: 'DEVELOPMENT', loopBackCount: 2 }, { stageCode: 'QA', loopBackCount: 1 }],
    }
    render(<CycleIterationBadge ribbon={ribbon} />)
    expect(screen.getByText('3 reworks')).toBeInTheDocument()
  })

  it('reads the singular "rework" for exactly one', () => {
    render(
      <CycleIterationBadge
        ribbon={{ cycleNo: 1, iterationNo: 2, segments: [{ stageCode: 'DEVELOPMENT', loopBackCount: 1 }] }}
      />,
    )
    expect(screen.getByText('1 rework')).toBeInTheDocument()
  })

  it("shows a past cycle's own final iteration, not the ticket's live one — the cross-cycle trap", () => {
    // Cycle 1 sealed at iteration 2 after a QA rework; the ticket itself has
    // since reopened into cycle 2, whose own iteration is back to 1. The
    // badge must read whichever cycle `?cycle=` scoped `Ribbon` to, per
    // `TicketDetailService`'s own note on what that param filters.
    render(<CycleIterationBadge ribbon={{ cycleNo: 1, iterationNo: 2, isSealed: true, segments: [] }} />)
    expect(screen.getByText('Cycle 1 · Iteration 2')).toBeInTheDocument()
  })
})
