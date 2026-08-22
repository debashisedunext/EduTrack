import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import type { Ribbon } from '@/api/generated/model/ribbon'
import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { RibbonStrip } from './RibbonStrip'

beforeAll(() => {
  // jsdom has neither — B-053's auto-centre calls `scrollIntoView`, and
  // `prefersReducedMotion` asks `matchMedia`. `DashboardPage.test.tsx`'s
  // `useCountUp` polyfill and `HandoffDialog.test.tsx`'s own `scrollIntoView`
  // stub use the identical shapes.
  Element.prototype.scrollIntoView ??= vi.fn()
  window.matchMedia ??= ((query: string) =>
    ({
      matches: false,
      media: query,
      addEventListener() {},
      removeEventListener() {},
    }) as unknown as MediaQueryList) as typeof window.matchMedia
})

beforeEach(() => {
  ;(Element.prototype.scrollIntoView as ReturnType<typeof vi.fn>).mockClear?.()
})

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

describe('RibbonStrip · C-047 currentStageAction', () => {
  it('renders the caller-supplied action on the current segment, alongside the handoff placeholder', () => {
    render(
      <RibbonStrip
        ribbon={{
          cycleNo: 1,
          iterationNo: 1,
          isSealed: false,
          canAdvance: true,
          segments: [seg({ stageCode: 'DEVELOPMENT', state: SegmentState.CURRENT })],
        }}
        currentStageAction={<button type="button">Skip stage</button>}
      />,
    )

    expect(screen.getByRole('button', { name: 'Skip stage' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Hand off/ })).toBeInTheDocument()
  })

  it('renders the caller-supplied action even when the caller may not otherwise advance', () => {
    render(
      <RibbonStrip
        ribbon={{
          cycleNo: 1,
          iterationNo: 1,
          isSealed: false,
          canAdvance: false,
          segments: [seg({ stageCode: 'DEVELOPMENT', state: SegmentState.CURRENT })],
        }}
        currentStageAction={<button type="button">Skip stage</button>}
      />,
    )

    expect(screen.getByRole('button', { name: 'Skip stage' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Hand off/ })).not.toBeInTheDocument()
  })

  it('renders nothing extra when the caller passes no action, same as before', () => {
    render(
      <RibbonStrip
        ribbon={{
          cycleNo: 1,
          iterationNo: 1,
          isSealed: false,
          canAdvance: false,
          segments: [seg({ stageCode: 'DEVELOPMENT', state: SegmentState.CURRENT })],
        }}
      />,
    )

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})

/**
 * B-052 · blueprint §4A.3's closing bullet — "fully keyboard-navigable".
 *
 * The per-segment ARIA label the same bullet asks for was B-050's and is
 * pinned in `segmentState.test.ts`; what is new here is that eight segments
 * are **one** stop on the way down the page, and that everything below the
 * ribbon is eight presses of Tab closer than it was.
 */
describe('RibbonStrip · B-052 keyboard navigation', () => {
  /** The strip's tab stops, in DOM order — the trigger of each tile. */
  function stops(): HTMLElement[] {
    return Array.from(document.querySelectorAll<HTMLElement>('[data-testid="ribbon-segment"] [tabindex]'))
  }

  function tabStops(): HTMLElement[] {
    return stops().filter((node) => node.getAttribute('tabindex') === '0')
  }

  function eightStages(currentAt = 3): RibbonSegmentData[] {
    const names = ['Intake', 'Triage', 'Development', 'QA', 'Deployment', 'Verification', 'Sign-off', 'Closed']
    return names.map((displayName, index) =>
      seg({
        stageCode: displayName.toUpperCase(),
        displayName,
        sequence: index + 1,
        state: index === currentAt ? SegmentState.CURRENT : SegmentState.COMPLETED,
      }),
    )
  }

  it('is one tab stop for the whole strip, not one per segment', () => {
    render(<RibbonStrip ribbon={ribbon(eightStages())} onSelectSegment={vi.fn()} />)

    expect(stops()).toHaveLength(8)
    expect(tabStops()).toHaveLength(1)
  })

  it('puts that tab stop on the current segment, not on the first', () => {
    render(<RibbonStrip ribbon={ribbon(eightStages(3))} onSelectSegment={vi.fn()} />)

    expect(tabStops()[0]).toHaveAccessibleName(/^QA,/)
  })

  it('falls back to the first segment when nothing is current — a sealed cycle', () => {
    render(
      <RibbonStrip
        ribbon={{
          cycleNo: 1,
          iterationNo: 1,
          isSealed: true,
          canAdvance: false,
          segments: eightStages(-1),
        }}
        onSelectSegment={vi.fn()}
      />,
    )

    expect(tabStops()[0]).toHaveAccessibleName(/^Intake,/)
  })

  it('tabs into the strip once and lands on the current stage', async () => {
    const user = userEvent.setup()
    render(<RibbonStrip ribbon={ribbon(eightStages(3))} onSelectSegment={vi.fn()} />)

    await user.tab()

    expect(document.activeElement).toHaveAccessibleName(/^QA,/)
  })

  it('moves right and left with the arrow keys', async () => {
    const user = userEvent.setup()
    render(<RibbonStrip ribbon={ribbon(eightStages(3))} onSelectSegment={vi.fn()} />)

    await user.tab()
    await user.keyboard('{ArrowRight}')
    expect(document.activeElement).toHaveAccessibleName(/^Deployment,/)

    await user.keyboard('{ArrowLeft}{ArrowLeft}')
    expect(document.activeElement).toHaveAccessibleName(/^Development,/)
  })

  it('jumps to the ends with Home and End, and wraps past them', async () => {
    const user = userEvent.setup()
    render(<RibbonStrip ribbon={ribbon(eightStages(3))} onSelectSegment={vi.fn()} />)

    await user.tab()
    await user.keyboard('{Home}')
    expect(document.activeElement).toHaveAccessibleName(/^Intake,/)

    await user.keyboard('{ArrowLeft}')
    expect(document.activeElement).toHaveAccessibleName(/^Closed,/)

    await user.keyboard('{ArrowRight}')
    expect(document.activeElement).toHaveAccessibleName(/^Intake,/)

    await user.keyboard('{End}')
    expect(document.activeElement).toHaveAccessibleName(/^Closed,/)
  })

  it('moves the tab stop with the focus, so Tab re-enters where the reader left', async () => {
    const user = userEvent.setup()
    render(<RibbonStrip ribbon={ribbon(eightStages(3))} onSelectSegment={vi.fn()} />)

    await user.tab()
    await user.keyboard('{ArrowRight}{ArrowRight}')

    expect(tabStops()).toHaveLength(1)
    expect(tabStops()[0]).toHaveAccessibleName(/^Verification,/)
  })

  /*
   * The one place this strip deliberately differs from `TicketDetailTabs`,
   * which selects on focus. Selecting here filters History, Effort and (once
   * `D-047` lands) Chat below — arrowing across eight stages to *read* the
   * ribbon would refilter three panels eight times.
   */
  it('does not select while the reader is only moving focus', async () => {
    const user = userEvent.setup()
    const onSelectSegment = vi.fn()
    render(<RibbonStrip ribbon={ribbon(eightStages(3))} onSelectSegment={onSelectSegment} />)

    await user.tab()
    await user.keyboard('{ArrowRight}{ArrowRight}{Home}{End}')

    expect(onSelectSegment).not.toHaveBeenCalled()
  })

  it('selects on Enter and on Space, which is what activation means here', async () => {
    const user = userEvent.setup()
    const onSelectSegment = vi.fn()
    render(<RibbonStrip ribbon={ribbon(eightStages(3))} onSelectSegment={onSelectSegment} />)

    await user.tab()
    await user.keyboard('{ArrowRight}')

    await user.keyboard('{Enter}')
    expect(onSelectSegment).toHaveBeenCalledWith(expect.objectContaining({ stageCode: 'DEPLOYMENT' }))

    onSelectSegment.mockClear()
    await user.keyboard('[Space]')
    expect(onSelectSegment).toHaveBeenCalledWith(expect.objectContaining({ stageCode: 'DEPLOYMENT' }))
  })

  it('leaves keys it does not own alone, so Tab still escapes the strip', async () => {
    const user = userEvent.setup()
    render(
      <>
        <RibbonStrip ribbon={ribbon(eightStages(3))} onSelectSegment={vi.fn()} />
        <button type="button">After the ribbon</button>
      </>,
    )

    await user.tab()
    expect(document.activeElement).toHaveAccessibleName(/^QA,/)

    await user.tab()
    expect(document.activeElement).toHaveAccessibleName('After the ribbon')
  })

  /*
   * S-13 tab 3's preview, S-30's designer preview and a sealed cycle all render
   * without `onSelectSegment`, so their tiles are `<div role="group">` —
   * unfocusable before this task, which made C-052's tooltip pointer-only on
   * all three. There is still nothing to activate, which is why they are not
   * buttons.
   */
  it('keeps a read-only ribbon navigable, with nothing to activate', async () => {
    const user = userEvent.setup()
    render(<RibbonStrip ribbon={ribbon(eightStages(3))} />)

    expect(screen.queryAllByRole('button')).toHaveLength(0)
    expect(stops()).toHaveLength(8)

    await user.tab()
    expect(document.activeElement).toHaveAccessibleName(/^QA,/)

    await user.keyboard('{ArrowRight}')
    expect(document.activeElement).toHaveAccessibleName(/^Deployment,/)
  })

  /*
   * §4A.3 puts the contextual action *on* the current segment, and B-050 read
   * that literally enough to render it inside the tile's own `<button>` — a
   * button nested in a button, which the HTML spec forbids and which no
   * keyboard can reach, because the outer control consumes Enter and Space.
   */
  it('renders the contextual action beside the segment trigger, never inside it', () => {
    render(
      <RibbonStrip
        ribbon={{
          cycleNo: 1,
          iterationNo: 1,
          isSealed: false,
          canAdvance: true,
          segments: eightStages(3),
        }}
        onSelectSegment={vi.fn()}
      />,
    )

    const action = screen.getByRole('button', { name: /Hand off to Deployment/ })
    const trigger = screen.getByRole('button', { name: /^QA,/ })

    expect(trigger).not.toContainElement(action)
    // The same card all the same — §4A.3 wants it on the current segment.
    expect(trigger.parentElement).toContainElement(action)
  })
})

/**
 * B-053 · §17's mitigation for "ribbon becomes unreadable at 8 stages on a
 * laptop" — the collapsed `…` group. `collapsedGroup.test.ts` pins which
 * stages collapse; this is the strip wiring it into the roving strip and
 * keeping every stage reachable once collapsed.
 */
describe('RibbonStrip · B-053 collapsed group', () => {
  function completedRun(count: number, offset = 0): RibbonSegmentData[] {
    return Array.from({ length: count }, (_, i) =>
      seg({
        stageCode: `STAGE${offset + i}`,
        displayName: `Stage ${offset + i}`,
        sequence: offset + i,
        state: SegmentState.COMPLETED,
      }),
    )
  }

  function fiveCompletedThenCurrent(): RibbonSegmentData[] {
    return [
      ...completedRun(5),
      seg({ stageCode: 'VERIFY', displayName: 'Verify', sequence: 6, state: SegmentState.CURRENT }),
      seg({ stageCode: 'SIGNOFF', displayName: 'Sign-off', sequence: 7, state: SegmentState.PENDING }),
      seg({ stageCode: 'CLOSED', displayName: 'Closed', sequence: 8, state: SegmentState.PENDING }),
    ]
  }

  it('collapses completed stages beyond the first three into one group tile', () => {
    render(<RibbonStrip ribbon={ribbon(fiveCompletedThenCurrent())} onSelectSegment={vi.fn()} />)

    // 3 completed + current + 2 pending — the other 2 completed are folded.
    expect(screen.getAllByTestId('ribbon-segment')).toHaveLength(6)
    expect(screen.getByTestId('ribbon-collapsed-group')).toBeInTheDocument()

    const group = screen.getByRole('button', { name: /^2 completed stages collapsed:/ })
    expect(group).toHaveAttribute('aria-expanded', 'false')
    expect(group).toHaveAccessibleName('2 completed stages collapsed: Stage 3, Stage 4')
  })

  it('does not collapse a run of three or fewer completed stages', () => {
    render(
      <RibbonStrip
        ribbon={ribbon([...completedRun(3), seg({ stageCode: 'QA', state: SegmentState.CURRENT })])}
      />,
    )

    expect(screen.queryByTestId('ribbon-collapsed-group')).not.toBeInTheDocument()
    expect(screen.getAllByTestId('ribbon-segment')).toHaveLength(4)
  })

  it('expands the group into its own segments on click, and folds them back on a second click', async () => {
    const user = userEvent.setup()
    render(<RibbonStrip ribbon={ribbon(fiveCompletedThenCurrent())} onSelectSegment={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /^2 completed stages collapsed:/ }))

    // The toggle itself stays on screen — it is now the "Collapse" control —
    // and the two segments it was hiding join it as ordinary tiles.
    const toggle = screen.getByRole('button', { name: /^Collapse 2 completed stages:/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('Stage 3')).toBeInTheDocument()
    expect(screen.getByText('Stage 4')).toBeInTheDocument()
    // 5 completed + current + 2 pending, all individual once expanded.
    expect(screen.getAllByTestId('ribbon-segment')).toHaveLength(8)

    await user.click(toggle)

    expect(screen.getByRole('button', { name: /^2 completed stages collapsed:/ })).toBeInTheDocument()
    expect(screen.queryByText('Stage 3')).not.toBeInTheDocument()
  })

  it('activates the group with Enter, same as any other tile', async () => {
    const user = userEvent.setup()
    render(<RibbonStrip ribbon={ribbon(fiveCompletedThenCurrent())} onSelectSegment={vi.fn()} />)

    act(() => {
      screen.getByRole('button', { name: /^2 completed stages collapsed:/ }).focus()
    })
    await user.keyboard('{Enter}')

    expect(screen.getByRole('button', { name: /^Collapse 2 completed stages:/ })).toBeInTheDocument()
  })

  it('counts the group as one roving stop, reachable by arrow keys like any segment', async () => {
    const user = userEvent.setup()
    render(<RibbonStrip ribbon={ribbon(fiveCompletedThenCurrent())} onSelectSegment={vi.fn()} />)

    // 3 individual completed + 1 group + current + 2 pending = 7 stops.
    const stops = document.querySelectorAll('[data-testid="ribbon-segment"] [tabindex], [data-testid="ribbon-collapsed-group"] [tabindex]')
    expect(stops).toHaveLength(7)

    await user.tab()
    expect(document.activeElement).toHaveAccessibleName(/^Verify,/)

    await user.keyboard('{ArrowLeft}')
    expect(document.activeElement).toHaveAccessibleName(/^2 completed stages collapsed:/)

    await user.keyboard('{ArrowLeft}')
    expect(document.activeElement).toHaveAccessibleName(/^Stage 2,/)
  })

  it('never selects a group tile — it is a display toggle, not a filter', async () => {
    const user = userEvent.setup()
    const onSelectSegment = vi.fn()
    render(<RibbonStrip ribbon={ribbon(fiveCompletedThenCurrent())} onSelectSegment={onSelectSegment} />)

    await user.click(screen.getByRole('button', { name: /^2 completed stages collapsed:/ }))
    expect(onSelectSegment).not.toHaveBeenCalled()
  })

  // A read-only ribbon still folds and unfolds its own completed run — the
  // toggle is a display decision, not a selection, so it does not depend on
  // `onSelectSegment` the way `RibbonSegment`'s own control-ness does.
  it('keeps the group expandable even on a read-only ribbon', async () => {
    const user = userEvent.setup()
    render(<RibbonStrip ribbon={ribbon(fiveCompletedThenCurrent())} />)

    await user.click(screen.getByRole('button', { name: /^2 completed stages collapsed:/ }))
    expect(screen.getByText('Stage 3')).toBeInTheDocument()
  })
})

/**
 * B-053 · the strip scrolls itself so the current segment is centred —
 * blueprint §17's other half of the same mitigation.
 */
describe('RibbonStrip · B-053 auto-centred scroll', () => {
  function scrollSpy() {
    return Element.prototype.scrollIntoView as ReturnType<typeof vi.fn>
  }

  it('centres the current segment on mount', () => {
    render(
      <RibbonStrip
        ribbon={ribbon([
          seg({ stageCode: 'INTAKE', displayName: 'Intake', state: SegmentState.COMPLETED }),
          seg({ stageCode: 'DEV', displayName: 'Development', state: SegmentState.CURRENT }),
        ])}
      />,
    )

    expect(scrollSpy()).toHaveBeenCalledWith(
      expect.objectContaining({ inline: 'center', block: 'nearest', behavior: 'smooth' }),
    )
  })

  it('scrolls instantly rather than smoothly when the reader prefers reduced motion', () => {
    const original = window.matchMedia
    window.matchMedia = ((query: string) =>
      ({ matches: true, media: query, addEventListener() {}, removeEventListener() {} }) as unknown as MediaQueryList) as typeof window.matchMedia

    try {
      render(
        <RibbonStrip
          ribbon={ribbon([seg({ stageCode: 'DEV', displayName: 'Development', state: SegmentState.CURRENT })])}
        />,
      )
      expect(scrollSpy()).toHaveBeenCalledWith(expect.objectContaining({ behavior: 'auto' }))
    } finally {
      window.matchMedia = original
    }
  })

  it('does not re-centre on a re-render that leaves the current stage unchanged', () => {
    const segments = [
      seg({ stageCode: 'INTAKE', displayName: 'Intake', state: SegmentState.COMPLETED }),
      seg({ stageCode: 'DEV', displayName: 'Development', state: SegmentState.CURRENT }),
    ]
    const { rerender } = render(<RibbonStrip ribbon={ribbon(segments)} selectedSegment={undefined} />)
    scrollSpy().mockClear()

    // Same current stage, different prop — selecting a tile must not re-fire
    // the centring effect, or arrowing around the strip would fight it.
    rerender(<RibbonStrip ribbon={ribbon(segments)} selectedSegment={{ stageCode: 'INTAKE' }} />)

    expect(scrollSpy()).not.toHaveBeenCalled()
  })

  it('re-centres when the current stage actually moves', () => {
    const before = [
      seg({ stageCode: 'INTAKE', displayName: 'Intake', state: SegmentState.COMPLETED }),
      seg({ stageCode: 'DEV', displayName: 'Development', state: SegmentState.CURRENT }),
      seg({ stageCode: 'QA', displayName: 'QA', state: SegmentState.PENDING }),
    ]
    const { rerender } = render(<RibbonStrip ribbon={ribbon(before)} />)
    scrollSpy().mockClear()

    const after = [
      seg({ stageCode: 'INTAKE', displayName: 'Intake', state: SegmentState.COMPLETED }),
      seg({ stageCode: 'DEV', displayName: 'Development', state: SegmentState.COMPLETED }),
      seg({ stageCode: 'QA', displayName: 'QA', state: SegmentState.CURRENT }),
    ]
    rerender(<RibbonStrip ribbon={ribbon(after)} />)

    expect(scrollSpy()).toHaveBeenCalledWith(expect.objectContaining({ inline: 'center' }))
  })
})
