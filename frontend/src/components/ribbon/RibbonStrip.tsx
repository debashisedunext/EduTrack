import * as React from 'react'
import type { ReactNode } from 'react'

import type { Ribbon } from '@/api/generated/model/ribbon'
import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { CollapsedGroupTile } from './CollapsedGroupTile'
import { buildRibbonRows } from './collapsedGroup'
import { RibbonSegment } from './RibbonSegment'
import { useRovingFocus } from './rovingFocus'
import { nextStageLabel } from './segmentState'

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
 * ## The contextual action button is an honest placeholder
 *
 * §4A.3 puts *Hand off to QA →* on the current segment, gated on
 * `Ribbon.canAdvance` — "whether **this caller** may advance the ticket …
 * hidden for everyone else" is the contract's own words for this task's
 * "hidden for everyone else" line. The dialog behind it is `C-044`, which has
 * not landed, so until then the button renders disabled and names the task
 * that finishes it — `TicketDetailHeader`'s `HEADER_ACTIONS` used the identical
 * pattern for Close and Reopen before C-039/C-040 wired them, and nothing here
 * invents a second one. The label itself reads the *next* segment's name off
 * the sibling list `RibbonSegment` does not have, on the same "Hand off to QA →"
 * wording the blueprint's own diagram uses.
 *
 * ## `currentStageAction` — C-047's skip trigger, and why it is a prop rather
 * ## than a second placeholder built in here
 *
 * `components/ribbon/` is the shared component library every stream consumes
 * — `segmentState.ts`'s own note declines the opposite import direction for
 * exactly this reason — so a real, working `SkipStageDialog` (which needs
 * `useSkipStageMutation`, toasts and a ticket) belongs in
 * `features/tickets/detail/`, not here. `TicketDetailPage` decides *whether*
 * to render one (`detail.availableActions.includes('skip-stage')`, C-047's
 * own addition to `TicketDetailService.availableActions`) and hands the
 * finished node down, the same "the caller decides, the ribbon reports"
 * split `onSelectSegment` already drew above. Rendered only on the current
 * segment, alongside the honest handoff placeholder rather than replacing
 * it — a skip and a handoff are both things the current owner might do next,
 * not alternatives to choose between at this layer.
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
   * C-047 · a finished trigger (today, `SkipStageDialog`) for the current
   * segment, supplied by the caller — see the class javadoc's own section on
   * why this is a prop rather than a second placeholder built in here.
   * Rendered only alongside the current segment, and only when the caller
   * passes something; `undefined` renders nothing extra, same as today.
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

  const scrollRef = React.useRef<HTMLDivElement | null>(null)
  const currentKey = currentSegment ? `${currentSegment.stageCode ?? ''}:${currentSegment.iterationNo ?? 1}` : undefined

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
              actionSlot={
                isCurrent && (ribbon?.canAdvance || currentStageAction) ? (
                  <>
                    {ribbon?.canAdvance && <HandoffPlaceholder label={nextStageLabel(segments, currentIndex)} />}
                    {currentStageAction}
                  </>
                ) : undefined
              }
            />
          </div>
        )
      })}
    </div>
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

/**
 * Rendered, disabled, and honest about why — `ribbon.canAdvance` already means
 * the server checked the golden rule and said this caller may act, so hiding
 * the button would misreport that, and enabling it would submit nothing.
 * `C-044`'s handoff dialog replaces this with a working trigger.
 */
function HandoffPlaceholder({ label }: { label: string }) {
  return (
    <Button
      type="button"
      size="sm"
      disabled
      className="w-full justify-center truncate px-2 text-caption"
      title={`${label} arrives with C-044`}
    >
      {label}
    </Button>
  )
}
