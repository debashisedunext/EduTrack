import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { RibbonSegment } from './RibbonSegment'

/**
 * The five data points §4A.3 requires, in all six states, plus the two things
 * that are easy to get quietly wrong: the live timer's *unit*, and whether a
 * read-only segment is still announced.
 *
 * Colours are not asserted — `segmentState.test.ts` asserts the vocabulary and
 * that tokens are used; a class list here would break on a restyle.
 */

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
    idleMins: 2070,
    iterationNo: 1,
    loopBackCount: 0,
    ...over,
  }
}

afterEach(() => {
  vi.useRealTimers()
})

describe('RibbonSegment · the five data points', () => {
  it('renders stage, owner, time in stage and effort', () => {
    render(<RibbonSegment segment={seg()} />)

    expect(screen.getByText('Development')).toBeInTheDocument()
    expect(screen.getByText('Ravi Kumar')).toBeInTheDocument()
    expect(screen.getByText('2d 1h in stage')).toBeInTheDocument()
    expect(screen.getByText('14.5 h effort')).toBeInTheDocument()
  })

  it('shows the loop-back badge only when the stage bounced', () => {
    const { rerender } = render(<RibbonSegment segment={seg({ loopBackCount: 0 })} />)
    expect(screen.queryByText('×2')).not.toBeInTheDocument()

    rerender(<RibbonSegment segment={seg({ loopBackCount: 2 })} />)
    expect(screen.getByText('×2')).toBeInTheDocument()
  })

  // The badge is driven by the count, not by the REWORKED state — a stage that
  // bounced twice and is now the one being worked on is CURRENT, and bounced.
  it('shows the loop badge on a current segment that has bounced', () => {
    render(<RibbonSegment segment={seg({ state: SegmentState.CURRENT, loopBackCount: 2 })} />)
    expect(screen.getByText('×2')).toBeInTheDocument()
  })

  it('names the owning role on a stage nobody has held yet', () => {
    render(<RibbonSegment segment={seg({ state: SegmentState.PENDING, owner: undefined, ownerRole: 'QA' })} />)
    expect(screen.getByText('QA · unassigned')).toBeInTheDocument()
  })

  it('renders an unmeasured duration as a dash, never as zero', () => {
    render(<RibbonSegment segment={seg({ state: SegmentState.PENDING, durationMins: null, effortHrs: undefined })} />)
    expect(screen.getByText('— in stage')).toBeInTheDocument()
    expect(screen.getByText('— effort')).toBeInTheDocument()
  })
})

describe('RibbonSegment · the six states', () => {
  it.each([
    [SegmentState.COMPLETED, 'Completed'],
    [SegmentState.CURRENT, 'Now'],
    [SegmentState.PENDING, 'Pending'],
    [SegmentState.REWORKED, 'Reworked'],
    [SegmentState.SKIPPED, 'Skipped'],
    [SegmentState.BLOCKED, 'On hold'],
  ])('renders %s with its own word, not colour alone', (state, word) => {
    render(<RibbonSegment segment={seg({ state })} />)
    expect(screen.getByText(word)).toBeInTheDocument()
    expect(screen.getByTestId('ribbon-segment').querySelector('[data-state]')).toHaveAttribute('data-state', state)
  })

  it('puts the skip reason where a hover can find it', () => {
    render(<RibbonSegment segment={seg({ state: SegmentState.SKIPPED, skipReason: 'no QA needed' })} />)
    expect(screen.getByRole('group')).toHaveAttribute('title', 'Skipped: no QA needed')
  })

  it('falls back to the handoff note when there is no skip reason', () => {
    render(<RibbonSegment segment={seg({ handoffNote: 'passed to Anil for regression' })} />)
    expect(screen.getByRole('group')).toHaveAttribute('title', 'passed to Anil for regression')
  })

  it('marks the current segment as the current step for assistive tech', () => {
    render(<RibbonSegment segment={seg({ state: SegmentState.CURRENT })} />)
    expect(screen.getByRole('group')).toHaveAttribute('aria-current', 'step')
  })
})

describe('RibbonSegment · the live elapsed timer', () => {
  /**
   * The unit trap. Sealed segments carry *working* minutes from the server;
   * the open one has none, so the browser counts wall clock. The two are
   * printed under different words on purpose — a Friday-evening handoff reads
   * 62h here and ~8h once the server seals it.
   */
  it('counts wall-clock elapsed on the open stage and says so', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-01T11:15:00Z'))

    render(
      <RibbonSegment
        segment={seg({
          state: SegmentState.CURRENT,
          durationMins: null,
          exitedAt: null,
          enteredAt: '2026-08-01T09:00:00Z',
        })}
      />,
    )

    expect(screen.getByText('2h 15m elapsed')).toBeInTheDocument()
    expect(screen.queryByText(/in stage/)).not.toBeInTheDocument()
  })

  it('ticks without a re-render from the caller', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-01T09:30:00Z'))

    render(
      <RibbonSegment
        segment={seg({ state: SegmentState.CURRENT, durationMins: null, enteredAt: '2026-08-01T09:00:00Z' })}
      />,
    )
    expect(screen.getByText('30m elapsed')).toBeInTheDocument()

    act(() => {
      vi.advanceTimersByTime(60_000)
    })
    expect(screen.getByText('31m elapsed')).toBeInTheDocument()
  })

  // The server's working-hours figure always wins where it exists.
  it('prefers a server duration over the clock even on the current stage', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-04T09:00:00Z'))

    render(
      <RibbonSegment
        segment={seg({ state: SegmentState.CURRENT, durationMins: 480, enteredAt: '2026-08-01T09:00:00Z' })}
      />,
    )
    expect(screen.getByText('8h in stage')).toBeInTheDocument()
  })

  it('runs no timer on a segment that is not current', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-04T09:00:00Z'))

    render(<RibbonSegment segment={seg({ state: SegmentState.PENDING, durationMins: null })} />)
    expect(screen.getByText('— in stage')).toBeInTheDocument()
  })

  // Clock skew between server and browser is ordinary; "-1m" is not.
  it('never shows a negative elapsed', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-01T08:59:30Z'))

    render(
      <RibbonSegment
        segment={seg({ state: SegmentState.CURRENT, durationMins: null, enteredAt: '2026-08-01T09:00:00Z' })}
      />,
    )
    expect(screen.getByText('0m elapsed')).toBeInTheDocument()
  })
})

describe('RibbonSegment · selection', () => {
  it('is a button that fires onSelect when the strip wires one', async () => {
    const onSelect = vi.fn()
    const segment = seg()
    render(<RibbonSegment segment={segment} onSelect={onSelect} />)

    await userEvent.click(screen.getByRole('button'))
    expect(onSelect).toHaveBeenCalledWith(segment)
  })

  it('is reachable and activated from the keyboard', async () => {
    const onSelect = vi.fn()
    render(<RibbonSegment segment={seg()} onSelect={onSelect} />)

    await userEvent.tab()
    expect(screen.getByRole('button')).toHaveFocus()
    await userEvent.keyboard('{Enter}')
    expect(onSelect).toHaveBeenCalledTimes(1)
  })

  /**
   * A sealed cycle's ribbon is read-only. Rendering a focusable control that
   * does nothing is worse than rendering none — it invites a click that has no
   * effect, which reads as a broken page rather than a historical one.
   */
  it('is not a control when no onSelect is given', () => {
    render(<RibbonSegment segment={seg()} />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByRole('group')).toBeInTheDocument()
  })

  it('still carries its accessible name when read-only', () => {
    render(<RibbonSegment segment={seg()} />)
    expect(screen.getByRole('group')).toHaveAccessibleName(
      'Development, Ravi Kumar, completed, 2d 1h in stage, 14.5 h effort',
    )
  })

  it('reports selection state to assistive tech', () => {
    render(<RibbonSegment segment={seg()} onSelect={vi.fn()} isSelected />)
    expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'true')
  })

  it('renders the contextual action slot the current segment carries', () => {
    render(<RibbonSegment segment={seg({ state: SegmentState.CURRENT })} actionSlot={<button>Hand off to QA</button>} />)
    expect(screen.getByRole('button', { name: 'Hand off to QA' })).toBeInTheDocument()
  })
})

describe('RibbonSegment · the connector', () => {
  it('draws a connector to the right by default', () => {
    render(<RibbonSegment segment={seg()} />)
    expect(screen.getByTestId('ribbon-connector')).toBeInTheDocument()
  })

  it('draws none on the last segment', () => {
    render(<RibbonSegment segment={seg()} isLast />)
    expect(screen.queryByTestId('ribbon-connector')).not.toBeInTheDocument()
  })
})
