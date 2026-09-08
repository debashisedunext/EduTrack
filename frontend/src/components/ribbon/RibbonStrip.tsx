import * as React from 'react'
import type { ReactNode } from 'react'

import type { Ribbon } from '@/api/generated/model/ribbon'
import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { EmptyState } from '@/components/ui/empty-state'
import { CollapsedGroupTile } from './CollapsedGroupTile'
import { buildRibbonRows } from './collapsedGroup'
import { RibbonSegment } from './RibbonSegment'
import { useRovingFocus } from './rovingFocus'

/** Identifies one segment for the selection C-052 drives — a stage code alone
 * is not unique once a ticket has looped, so the iteration travels with it. */
export interface SelectedSegment {
  stageCode: string
  iterationNo?: number
}

function isSameSegment(selected: SelectedSegment | undefined, segment: RibbonSegmentData): boolean {
  if (!selected || !segment.stageCode) return false
  return selected.stageCode === segment.stageCode && (selected.iterationNo ?? 1) === (segment.iterationNo ?? 1)
}

/**
 * C-051 lays B-050's tiles out into the strip blueprint §4A.3 describes —
 * the ribbon pinned to the top of every ticket detail page. C-052 wires what
 * a click does and the current segment's contextual action.
 *
 * ## Selection is controlled, not owned here
 *
 * `selectedSegment`/`onSelectSegment` come from `TicketDetailPage`, which also
 * filters History and Effort below to the clicked stage and iteration —
 * `RibbonStrip` only tells `RibbonSegment` which tile is selected and forwards
 * clicks, the same "the caller decides, the ribbon reports" split `onSelect`
 * already drew at the segment level. Whether a second click on the already-
 * selected tile clears the filter is the caller's call too; this strip fires
 * on every click regardless.
 *
 * Wired **even when `ribbon.isSealed`.** A past, closed cycle's journey is
 * read-only for advancing it, not for reading it — filtering that cycle's own
 * History and Effort to one of its stages is exactly as useful as it is on the
 * live cycle, so selection is never gated on `isSealed`.
 *
 * ## `currentStageAction` — the current segment's real triggers, composed by the caller
 *
 * `components/ribbon/` is the shared component library every stream consumes
 * — `segmentState.ts`'s own note declines the opposite import direction for
 * exactly this reason — so real, working triggers (`HandoffDialog`,
 * `SkipStageDialog` — each needs its own mutation hook, toasts and a ticket)
 * belong in `features/tickets/detail/`, not here. `TicketDetailPage` decides
 * *which* to render (`detail.availableActions` naming `handoff`/`skip-stage`,
 * `TicketDetailService.availableActions`'s own vocabulary) and hands the
 * finished node down, the same "the caller decides, the ribbon reports" split
 * `onSelectSegment` already drew above. This strip used to fill the slot
 * itself with a disabled placeholder gated on `ribbon.canAdvance` — replaced
 * outright once `HandoffDialog` had a real trigger site, not layered under it,
 * since a caller with nothing to hand `currentStageAction` now has nothing to
 * show rather than a button naming a task that already shipped.
 *
 * ## What this task is not
 *
 * - **The cycle selector and the `Cycle 2 · Iteration 3` chips.** `C-053` and
 *   `C-054` render above this strip; this component only ever draws the one
 *   cycle's segments it is given; which cycle is already resolved by
 *   `GET /tickets/{id}/full`'s own `?cycle=` (C-019), so there is nothing
 *   here to select from.
 * - **The Chat tab.** It filters to stage and iteration exactly like History
 *   and Effort once it exists, but `TicketDetailPage`'s Chat tab is still
 *   `D-047`'s placeholder — there is nothing there yet for a filter to reach.
 *
 * `role="list"`/`role="listitem"` rather than nothing: `TicketDetailPage`'s
 * own tests already distinguish "the ribbon's list" from the description's
 * `<ol>` and the tab strip's `tablist`, and a screen reader announcing "list,
 * 8 items" here is the ribbon's shape, the same way the roll-up grid's rows
 * are a table.
 *
 * ## B-052 · one tab stop, arrows inside it
 *
 * §4A.3's closing bullet asks for a ribbon that is *fully keyboard-navigable*,
 * and eight tab stops is the way to fail that while appearing to satisfy it: a
 * ticket detail page puts History, Effort, Chat and the roll-up grid **below**
 * the ribbon, so eight segments is eight presses of Tab standing between a
 * keyboard reader and everything they came for — and sixteen on a ticket that
 * has looped. So the strip is a composite widget: one element in the tab order,
 * `←`/`→` between segments, `Home`/`End` to the ends, all of it in
 * `useRovingFocus` beside this file.
 *
 * The tab stop **starts on the current segment**, which is the one thing the
 * ribbon exists to say. Landing a reader on Intake — finished four days ago —
 * and making them arrow forward to find out where the ticket actually is would
 * be a working keyboard interface answering the wrong question.
 *
 * **Every tile joins the roving, including the read-only ones.** A strip
 * rendered without `onSelectSegment` — a sealed cycle, S-13 tab 3's live
 * preview, S-30's designer preview — draws `<div role="group">` tiles that
 * nothing could focus before this task, so C-052's rich tooltip (entered,
 * exited, owner, note, effort, idle-vs-active) was pointer-only on all three.
 * Passing `tabIndex` is what makes them reachable; there is still nothing to
 * activate, which is correct and is why they are not buttons.
 *
 * **Focus does not select** — `useRovingFocus`'s own header argues it against
 * `TicketDetailTabs`, which does the opposite for a good reason that does not
 * hold here.
 *
 * ## B-053 · readability at eight stages
 *
 * §17's mitigation for "ribbon becomes unreadable at 8 stages on a laptop" is
 * two behaviours, both here rather than in `RibbonSegment` — a tile does not
 * know how many siblings it has, or where the strip has scrolled to.
 *
 * **Collapsing** is `collapsedGroup.ts`'s `buildRibbonRows`: completed stages
 * beyond the first three become one `CollapsedGroupTile` instead of one tile
 * each, and that tile joins the roving same as any segment — `rows.length`,
 * not `segments.length`, is what `useRovingFocus` counts, so Home/End and the
 * arrow keys land on it exactly like any other stop. Expanding one is a
 * `Set<string>` of group keys kept here, not in the pure builder, on the same
 * split `rovingFocus.ts` draws between `nextFocusIndex` and the hook around
 * it. A group's segments are never dropped from `ribbon.segments` — only from
 * which row renders them — so expanding it does not re-fetch anything.
 *
 * ## C-116 · what a reader who cannot see the strip was missing
 *
 * Two of the three fixes are in `segmentState.ts` — the spoken position, and
 * the live clock no longer being announced under the sealed figure's words.
 * See `segmentAriaLabel`'s own header for both. This file supplies the
 * position (**1-based over `ribbon.segments`, never over `rows`**, so folding
 * a `…` group does not renumber the stages behind it) and owns the third:
 *
 * **A handoff was silent.** The strip re-renders on every `stage.changed`
 * frame `D-058` pushes, and a ticket moving from Development to QA under an
 * open page produced a scroll, a moved `CURRENT` tile and a changed colour —
 * three signals, all of them visual. A screen-reader user was told nothing at
 * all, which is WCAG 2.1 4.1.3 (Status Messages, AA) and is CLAUDE.md's
 * "accessibility is not optional" line at its most concrete. There is now a
 * polite live region below, announcing the stage the ticket has moved into.
 *
 * It is deliberately **silent on the first resolution**. A live region that
 * speaks when the ribbon first loads is announcing the page, not a change,
 * and it would talk over whatever the reader was actually navigating to. So
 * the first current stage is recorded without being spoken, and only a
 * genuine move from one stage to another is news. The same reasoning keys it
 * on the current segment's own identity — stage code plus iteration — as the
 * auto-centring effect below: a ticket sent *back* to Development is a real
 * move and says so, because the iteration differs even though the stage does
 * not.
 *
 * **Auto-centring** depends on the *current stage*, not on which row holds
 * the roving tab stop. Arrowing across the strip to read it must not drag the
 * scroll position along for the same reason B-052's tab stop does not follow
 * a live `stage.changed` frame once a reader has moved it — the two are
 * independent axes, and conflating them would re-centre the strip every time
 * someone reads a segment that is not the current one. So the effect below
 * keys on the current segment's own identity and nothing else, and only ever
 * moves the strip when the ticket's stage genuinely changes.
 */
export function RibbonStrip({
  ribbon,
  selectedSegment,
  onSelectSegment,
  currentStageAction,
}: {
  ribbon?: Ribbon
  /** The stage+iteration currently filtering History/Effort below, or `undefined` for none. */
  selectedSegment?: SelectedSegment
  /** Fired on every segment click, current cycle or sealed. */
  onSelectSegment?: (segment: RibbonSegmentData) => void
  /**
   * Finished triggers for the current segment (`HandoffDialog`,
   * `SkipStageDialog`, composed together by the caller when both apply) — see
   * the class javadoc's own section on why this is a prop rather than
   * anything built in here. Rendered only alongside the current segment, and
   * only when the caller passes something; `undefined` renders nothing.
   */
  currentStageAction?: ReactNode
}) {
  const segments = React.useMemo(() => ribbon?.segments ?? [], [ribbon?.segments])
  const currentIndex = segments.findIndex((segment) => segment.state === SegmentState.CURRENT)
  const currentSegment = currentIndex >= 0 ? segments[currentIndex] : undefined

  const [expandedGroups, setExpandedGroups] = React.useState<ReadonlySet<string>>(() => new Set())
  const rows = React.useMemo(() => buildRibbonRows(segments, expandedGroups), [segments, expandedGroups])
  const currentRowIndex = rows.findIndex((row) => row.kind === 'segment' && row.segment.state === SegmentState.CURRENT)

  // Above the empty-state return, because hooks are. A ribbon with no segments
  // rovers over nothing, which `useRovingFocus` handles rather than divides by.
  const roving = useRovingFocus(rows.length, Math.max(currentRowIndex, 0))

  // Identity, not a search per tile: `buildRibbonRows` hands back the same
  // objects it was given, collapsed or not, so one pass builds the whole map
  // and a collapsed run keeps the numbers of the stages it folded.
  const positionOf = React.useMemo(() => {
    const map = new Map<RibbonSegmentData, number>()
    segments.forEach((segment, index) => map.set(segment, index + 1))
    return map
  }, [segments])

  const scrollRef = React.useRef<HTMLDivElement | null>(null)
  const currentKey = currentSegment ? `${currentSegment.stageCode ?? ''}:${currentSegment.iterationNo ?? 1}` : undefined

  /*
   * C-116 · the handoff a screen reader could not hear. See the docstring.
   *
   * The sentence is held in a ref rather than named in the effect's deps: the
   * effect must fire on a change of *stage*, and putting the rendered label in
   * there would also fire it when a stage was merely renamed under an open
   * page. Reading the ref is what keeps "when to speak" and "what to say" two
   * separate questions.
   */
  const [stageAnnouncement, setStageAnnouncement] = React.useState('')
  const currentLabel = currentSegment
    ? `${currentSegment.displayName ?? currentSegment.stageCode ?? 'Stage'}` +
      ((currentSegment.iterationNo ?? 1) > 1 ? `, iteration ${currentSegment.iterationNo}` : '')
    : undefined
  const latestLabel = React.useRef(currentLabel)
  latestLabel.current = currentLabel

  const announcedKey = React.useRef<string | undefined>(undefined)
  React.useEffect(() => {
    if (!currentKey) return
    const previous = announcedKey.current
    announcedKey.current = currentKey
    // `undefined` is the ribbon arriving, not the ticket moving.
    if (previous === undefined || previous === currentKey) return
    setStageAnnouncement(`Now in ${latestLabel.current}`)
  }, [currentKey])

  React.useEffect(() => {
    if (!currentKey) return
    const node = scrollRef.current?.querySelector<HTMLElement>('[data-ribbon-current="true"]')
    // `HandoffDialog.tsx`'s own scroll-to-bottom is optional-chained the same
    // way — jsdom has no `scrollIntoView` at all, and a real browser without
    // one is not a case this strip needs to survive differently.
    node?.scrollIntoView?.({
      inline: 'center',
      block: 'nearest',
      behavior: prefersReducedMotion() ? 'auto' : 'smooth',
    })
    // Only the current stage's own identity — see the docstring above for why
    // the roving tab stop and the group-expansion state must not be here too.
  }, [currentKey])

  const toggleGroup = (key: string) => {
    setExpandedGroups((prev) => {
      const next = new Set(prev)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }

  if (segments.length === 0) {
    return (
      <EmptyState
        title="No workflow ribbon"
        description="This ticket has no workflow template, so there is no stage journey to show."
      />
    )
  }

  return (
    <>
      {/*
        Outside the list, so it is neither an unlabelled `listitem` nor inside
        a container a reader may be browsing. Present from first paint, because
        a live region added to the DOM at the same moment its text changes is a
        region most screen readers never announce.

        **`aria-live` and `aria-atomic` rather than `role="status"`**, which is
        what those two attributes *are*. The announcement is identical and the
        role is not, and the difference matters because this strip is shared:
        `TicketDetailPage` already renders a `role="status"` banner for a
        sealed cycle, and `attachment-picker.tsx` documents having avoided
        landing "a second `role="status"` beside" it for the same reason. A
        second one here would not break a screen reader — but it would make
        `getByRole('status')` ambiguous on every page that mounts a ribbon,
        which is a shared component making three other people's tests
        someone's problem. Contributing no role at all costs nothing here.
      */}
      <p
        aria-live="polite"
        aria-atomic="true"
        data-testid="ribbon-stage-announcement"
        className="sr-only"
      >
        {stageAnnouncement}
      </p>
      <div
        ref={scrollRef}
        role="list"
        aria-label="Workflow stages"
        // One listener on the container rather than eight on the tiles, and the
        // one place a key that is not ours is left alone — `nextFocusIndex`
        // returns null and nothing is prevented, so Tab still leaves the strip.
        onKeyDown={roving.onKeyDown}
        className="flex items-start overflow-x-auto pb-1"
      >
        {rows.map((row, index) => {
          const { ref, tabIndex, onFocus } = roving.itemProps(index)
          const isLastRow = index === rows.length - 1

          if (row.kind === 'group') {
            return (
              <div role="listitem" key={`group:${row.key}`}>
                <CollapsedGroupTile
                  ref={ref}
                  tabIndex={tabIndex}
                  onFocus={onFocus}
                  segments={row.segments}
                  expanded={row.expanded}
                  onToggle={() => toggleGroup(row.key)}
                  isLast={isLastRow}
                />
              </div>
            )
          }

          const segment = row.segment
          const isCurrent = segment.state === SegmentState.CURRENT
          const index1Based = positionOf.get(segment)
          return (
            <div
              role="listitem"
              key={`${segment.stageCode ?? 'segment'}:${segment.iterationNo ?? 1}`}
              data-ribbon-current={isCurrent || undefined}
            >
              <RibbonSegment
                ref={ref}
                tabIndex={tabIndex}
                onFocus={onFocus}
                segment={segment}
                isLast={isLastRow}
                onSelect={onSelectSegment}
                isSelected={isSameSegment(selectedSegment, segment)}
                actionSlot={isCurrent && currentStageAction ? currentStageAction : undefined}
                position={index1Based ? { index: index1Based, total: segments.length } : undefined}
              />
            </div>
          )
        })}
      </div>
    </>
  )
}

/**
 * `useCountUp.ts`'s own reduced-motion check, repeated rather than shared —
 * `features/dashboard/` and `components/ribbon/` don't import from each
 * other, and a two-line `typeof window` guard is not worth a third module to
 * hold it. Honoured the same way: the scroll simply has no motion, it is not
 * skipped.
 */
function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}
