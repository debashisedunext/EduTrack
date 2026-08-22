import { createRef } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { format, parseISO } from 'date-fns'

import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { RibbonSegment } from './RibbonSegment'

// Matches `segmentState.ts`'s own `TOOLTIP_TIMESTAMP_FORMAT`. Computed rather
// than hardcoded: `format` renders in the machine's local timezone (CLAUDE.md
// — "user timezone is applied in the presentation layer only"), so a literal
// clock-time string here would pass in UTC and fail everywhere east of it.
function stamp(iso: string): string {
  return format(parseISO(iso), 'd MMM yyyy, HH:mm')
}

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

describe('RibbonSegment · C-052 the rich hover tooltip', () => {
  it('shows entered, exited, owner, effort and the idle-vs-active split on hover', async () => {
    // idleMins 600 against durationMins 2940 (20%) stays under the
    // idle-dominated threshold, so the idle figure carries no sr-only
    // suffix here — that combination is its own test below.
    render(<RibbonSegment segment={seg({ idleMins: 600 })} />)

    await userEvent.hover(screen.getByRole('group'))
    const tooltip = await screen.findByRole('tooltip')

    expect(within(tooltip).getByText(stamp('2026-08-01T09:00:00Z'))).toBeInTheDocument()
    expect(within(tooltip).getByText(stamp('2026-08-03T13:00:00Z'))).toBeInTheDocument()
    expect(within(tooltip).getByText('Ravi Kumar')).toBeInTheDocument()
    expect(within(tooltip).getByText('14.5 h')).toBeInTheDocument()
    // durationMins 2940, idleMins 600 → active 2340 = 1d 15h.
    expect(within(tooltip).getByText('1d 15h')).toBeInTheDocument()
    expect(within(tooltip).getByText('10h')).toBeInTheDocument()
  })

  it('is reachable by keyboard focus, not only pointer hover', async () => {
    render(<RibbonSegment segment={seg()} onSelect={vi.fn()} />)

    await userEvent.tab()
    expect(await screen.findByRole('tooltip')).toBeInTheDocument()
  })

  it('puts the skip reason in the note field', async () => {
    render(<RibbonSegment segment={seg({ state: SegmentState.SKIPPED, skipReason: 'no QA needed' })} />)

    await userEvent.hover(screen.getByRole('group'))
    const tooltip = await screen.findByRole('tooltip')
    expect(within(tooltip).getByText('Skipped: no QA needed')).toBeInTheDocument()
  })

  it('falls back to the handoff note when there is no skip reason', async () => {
    render(<RibbonSegment segment={seg({ handoffNote: 'passed to Anil for regression' })} />)

    await userEvent.hover(screen.getByRole('group'))
    const tooltip = await screen.findByRole('tooltip')
    expect(within(tooltip).getByText('passed to Anil for regression')).toBeInTheDocument()
  })

  it('shows a dash for the note when neither exists', async () => {
    render(<RibbonSegment segment={seg({ handoffNote: undefined })} />)

    await userEvent.hover(screen.getByRole('group'))
    const tooltip = await screen.findByRole('tooltip')
    expect(within(tooltip).getByText('—')).toBeInTheDocument()
  })

  it('reads still-open and not-yet-measured on the live current stage', async () => {
    render(
      <RibbonSegment
        segment={seg({ state: SegmentState.CURRENT, exitedAt: null, durationMins: null, idleMins: null })}
      />,
    )

    await userEvent.hover(screen.getByRole('group'))
    const tooltip = await screen.findByRole('tooltip')
    // Exited reads "still open"; active and idle both read "not yet measured"
    // — durationMins and idleMins are both null until the hop seals.
    expect(within(tooltip).getByText('Still open')).toBeInTheDocument()
    expect(within(tooltip).getAllByText('Not yet measured')).toHaveLength(2)
  })

  // A stage where waiting dominates is marked, colour is never the only
  // signal — the Journey grid's own idle column follows the identical rule.
  it('flags an idle-dominated stage for sighted and screen-reader users alike', async () => {
    render(<RibbonSegment segment={seg({ durationMins: 2940, idleMins: 2070 })} />)

    await userEvent.hover(screen.getByRole('group'))
    await screen.findByRole('tooltip')
    expect(screen.getByText(/^— most of this stage was spent waiting, not working$/)).toBeInTheDocument()
  })

  it('does not flag a stage where active time dominates', async () => {
    render(<RibbonSegment segment={seg({ durationMins: 480, idleMins: 60 })} />)

    await userEvent.hover(screen.getByRole('group'))
    const tooltip = await screen.findByRole('tooltip')
    expect(within(tooltip).queryByText(/most of this stage was spent waiting/)).not.toBeInTheDocument()
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

/**
 * B-052 · what the tile owes the strip's roving tab stop, and the nested
 * control it stopped rendering to make room for it.
 *
 * The arrow keys themselves are `RibbonStrip`'s and are tested there. These
 * are the three things the tile has to get right for that to work at all: it
 * takes a `tabIndex`, it forwards a ref to whichever element that lands on,
 * and it no longer puts a `<button>` inside a `<button>`.
 */
describe('RibbonSegment · B-052 accessibility', () => {
  it('takes the strip tab stop on its button', () => {
    render(<RibbonSegment segment={seg()} onSelect={vi.fn()} tabIndex={0} />)
    expect(screen.getByRole('button')).toHaveAttribute('tabindex', '0')
  })

  it('takes -1 when another segment holds the strip tab stop', () => {
    render(<RibbonSegment segment={seg()} onSelect={vi.fn()} tabIndex={-1} />)
    expect(screen.getByRole('button')).toHaveAttribute('tabindex', '-1')
  })

  /*
   * A read-only tile is a `<div role="group">` and was unfocusable before this
   * task, which left C-052's tooltip — entered, exited, owner, note, effort,
   * idle-vs-active — with no keyboard route on a sealed cycle, on S-13 tab 3's
   * preview or in S-30's designer. It is still not a button, because there is
   * still nothing to activate.
   */
  it('makes a read-only tile focusable when the strip hands it a tab stop', () => {
    render(<RibbonSegment segment={seg()} tabIndex={0} />)
    expect(screen.getByRole('group')).toHaveAttribute('tabindex', '0')
  })

  it('leaves a tile with no tab stop out of the tab order entirely', () => {
    render(<RibbonSegment segment={seg()} />)
    expect(screen.getByRole('group')).not.toHaveAttribute('tabindex')
  })

  it('reports focus so the strip can move its tab stop to a clicked tile', async () => {
    const onFocus = vi.fn()
    render(<RibbonSegment segment={seg()} onSelect={vi.fn()} tabIndex={0} onFocus={onFocus} />)

    await userEvent.tab()

    expect(document.activeElement).toBe(screen.getByRole('button'))
    expect(onFocus).toHaveBeenCalled()
  })

  it('forwards a ref to the focusable element, which is what the strip focuses', () => {
    const ref = createRef<HTMLElement>()
    render(<RibbonSegment segment={seg()} onSelect={vi.fn()} ref={ref} />)

    expect(ref.current).toBe(screen.getByRole('button'))
  })

  it('forwards that ref to the read-only tile too', () => {
    const ref = createRef<HTMLElement>()
    render(<RibbonSegment segment={seg()} ref={ref} />)

    expect(ref.current).toBe(screen.getByRole('group'))
  })

  /*
   * The defect B-050 shipped and this task fixes. `actionSlot` rendered inside
   * the tile's own `<button>`, so §4A.3's *Hand off to QA →* was a button
   * nested in a button: invalid HTML, recovered from differently by every
   * browser, and unreachable by keyboard because the outer control consumes
   * Enter and Space. It is now a sibling within the card.
   */
  it('renders the contextual action outside the tile button, not inside it', () => {
    render(
      <RibbonSegment
        segment={seg({ state: SegmentState.CURRENT })}
        onSelect={vi.fn()}
        actionSlot={<button type="button">Hand off to QA</button>}
      />,
    )

    const action = screen.getByRole('button', { name: 'Hand off to QA' })
    const trigger = screen.getByRole('button', { name: /^Development,/ })

    expect(trigger).not.toContainElement(action)
    expect(trigger.parentElement).toContainElement(action)
  })

  it('leaves the action reachable by Tab after the segment itself', async () => {
    render(
      <RibbonSegment
        segment={seg({ state: SegmentState.CURRENT })}
        onSelect={vi.fn()}
        tabIndex={0}
        actionSlot={<button type="button">Hand off to QA</button>}
      />,
    )

    await userEvent.tab()
    expect(document.activeElement).toHaveAccessibleName(/^Development,/)

    await userEvent.tab()
    expect(document.activeElement).toHaveAccessibleName('Hand off to QA')
  })
})
