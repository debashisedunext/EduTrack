import * as React from 'react'
import * as AvatarPrimitive from '@radix-ui/react-avatar'
import { RotateCcw } from 'lucide-react'

import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { formatDuration, formatEffortHrs } from '@/lib/duration'
import { cn } from '@/lib/utils'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { ownerLabel, segmentAriaLabel, segmentTooltipDetails, treatmentFor } from './segmentState'
import { useElapsedMins } from './useElapsedMins'

/**
 * B-050 · one segment of the Workflow Ribbon — blueprint §4A.3.
 *
 * ```
 * ┌───────────────┐
 * │ ✔ Development │  ← stage name + state icon
 * │ Ravi Kumar    │  ← owner (avatar + name)
 * │ 3d 4h         │  ← time in stage (working hours)
 * │ 14.5 h effort │  ← effort logged in this stage
 * │ ↺ ×2          │  ← loop-back badge, only if bounced
 * └───────────────┘
 * ```
 *
 * All five, in that order, in all six states. It renders one segment and knows
 * nothing about the ones beside it — `C-051` lays them out, and the props here
 * are the whole contract between the two tasks.
 *
 * ## What this task is not
 *
 * - **C-052's tooltip is built, its filtering is not.** The tile now wraps
 *   itself in the shared `Tooltip` (`components/ui/tooltip.tsx`) and shows
 *   `segmentTooltipDetails` on hover and focus — entered, exited, owner,
 *   note, effort and the idle-vs-active split §4A.3 asks for. *What a click
 *   does* — filtering History and Effort below to this stage and iteration —
 *   is `RibbonStrip`'s and `TicketDetailPage`'s, which own the selection
 *   state this tile only reports through `onSelect`/`isSelected`.
 * - **`B-052`'s keyboard navigation.** A segment given `onSelect` is a real
 *   `<button>`, so it is focusable and Enter/Space work today. Arrow-key
 *   roving across the strip belongs to the strip.
 * - **`B-051`'s compact dot** and **`B-053`'s horizontal scroll and collapsed
 *   grouping**. This tile is fixed-width and content-sized; who scrolls it is
 *   the strip's problem.
 * - **The contextual action button's own content** (*Hand off to QA →*) that
 *   §4A.3 puts on the current segment is `RibbonStrip`'s to build — it needs
 *   `Ribbon.canAdvance` and the sibling segments to name the next stage,
 *   neither of which this tile has. `actionSlot` is only where it renders.
 *   The dialog it opens is still `C-044`; until then it is C-052's own
 *   honestly-disabled placeholder, the same pattern `TicketDetailHeader`'s
 *   `HEADER_ACTIONS` used for Close and Reopen before their tasks landed.
 *
 * ## The stage's own icon is not rendered, and that is deliberate
 *
 * `RibbonSegment.icon` carries a lucide name from the workflow template
 * (`inbox`, `rocket`, `flask-conical`, …), chosen by an Admin in `B-043`'s
 * designer. Rendering an arbitrary name means shipping the entire lucide set
 * or a dynamic import per icon, and no other screen in this codebase resolves
 * an icon name — every one of the forty-odd call sites imports the icons it
 * names. §4A.3's own diagram shows a **state** icon in that position (`✔`),
 * which is the six static imports in `segmentState.ts`. When `B-043` ships the
 * designer and the field is actually chosen by somebody, resolving it is that
 * task's to introduce, with one map for the whole app rather than one here.
 */
export interface RibbonSegmentProps {
  segment: RibbonSegmentData
  /** Suppresses the trailing connector. The strip sets it on the last tile. */
  isLast?: boolean
  /**
   * Fired on click and on Enter/Space. Presence of this prop is what makes the
   * tile a button: a ribbon rendered read-only for a past cycle (`isSealed`)
   * passes nothing and gets a plain, unfocusable `<div>` rather than a control
   * that looks live and does nothing.
   */
  onSelect?: (segment: RibbonSegmentData) => void
  /** Marks this tile as the selected filter — `C-052` drives it. */
  isSelected?: boolean
  /** §4A.3's inline contextual action on the current segment — `C-044`. */
  actionSlot?: React.ReactNode
  className?: string
}

function initials(name: string): string {
  return name
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('')
}

export function RibbonSegment({
  segment,
  isLast = false,
  onSelect,
  isSelected = false,
  actionSlot,
  className,
}: RibbonSegmentProps) {
  const treatment = treatmentFor(segment.state)
  const isCurrent = segment.state === SegmentState.CURRENT

  // The server's working-minutes figure wins wherever it exists; the live
  // clock is only for the open stage, which has none. See `useElapsedMins`.
  const elapsedMins = useElapsedMins(segment.enteredAt, isCurrent && segment.durationMins == null)
  const isLive = elapsedMins != null

  const owner = ownerLabel(segment)
  const stage = segment.displayName ?? segment.stageCode ?? 'Stage'
  const loops = segment.loopBackCount ?? 0
  const { Icon } = treatment

  // C-052 · §4A.3's hover: entered/exited/owner/note/effort/idle-vs-active.
  // `segmentTooltipDetails` is pure and tested on its own; this only lays the
  // six fields out.
  const tooltip = segmentTooltipDetails(segment)

  const body = (
    <>
      {/* 1 · stage name + state icon */}
      <div className="flex items-center gap-1.5">
        <Icon
          className={cn('h-4 w-4 shrink-0', isCurrent && 'animate-pulse motion-reduce:animate-none')}
          aria-hidden="true"
        />
        <span className={cn('truncate text-xs font-semibold', treatment.title)}>{stage}</span>
        {/* The state in words. Colour is never the only signal — tokens.css. */}
        <span className="ml-auto shrink-0 text-caption font-medium opacity-80">{treatment.label}</span>
      </div>

      {/* 2 · owner, avatar + name */}
      <div className="flex items-center gap-1.5">
        <AvatarPrimitive.Root
          className="inline-flex h-5 w-5 shrink-0 items-center justify-center overflow-hidden rounded-full bg-primary-soft text-[9px] font-semibold text-primary"
          aria-hidden="true"
        >
          {segment.owner?.avatarUrl && (
            <AvatarPrimitive.Image
              src={segment.owner.avatarUrl}
              alt=""
              className="h-full w-full object-cover"
            />
          )}
          <AvatarPrimitive.Fallback delayMs={segment.owner?.avatarUrl ? 300 : 0}>
            {segment.owner?.displayName ? initials(segment.owner.displayName) : '—'}
          </AvatarPrimitive.Fallback>
        </AvatarPrimitive.Root>
        <span className="truncate text-caption text-content-muted">{owner}</span>
      </div>

      {/* 3 · time in stage. `elapsed` and `in stage` are different units and
          are never printed under the same word — see `useElapsedMins`. */}
      <div
        className="text-caption tabular-nums text-content-muted"
        title={
          isLive
            ? 'Clock time since this stage was entered. The working-hours figure is measured when the stage is handed over.'
            : undefined
        }
      >
        {isLive ? `${formatDuration(elapsedMins)} elapsed` : `${formatDuration(segment.durationMins)} in stage`}
      </div>

      {/* 4 · effort logged in this stage */}
      <div className="text-caption tabular-nums text-content-muted">
        {formatEffortHrs(segment.effortHrs)} effort
      </div>

      {/* 5 · loop-back badge, only if bounced. Driven by the count and not by
          the REWORKED state: a stage that has bounced twice and is now the
          current one is CURRENT, and still bounced twice. */}
      {loops > 0 && (
        <span className="inline-flex w-fit items-center gap-0.5 rounded-chip bg-level-high-soft px-1.5 py-0.5 text-caption font-medium text-ribbon-reworked-text">
          <RotateCcw className="h-3 w-3" aria-hidden="true" />×{loops}
        </span>
      )}

      {actionSlot && <div className="pt-0.5">{actionSlot}</div>}
    </>
  )

  const card = cn(
    'flex w-40 shrink-0 flex-col gap-1 rounded-card border p-2.5 text-left shadow-rest transition',
    treatment.card,
    onSelect && 'cursor-pointer hover:shadow-modal focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1',
    isSelected && 'ring-2 ring-primary',
    className,
  )

  const trigger = onSelect ? (
    <button
      type="button"
      className={card}
      onClick={() => onSelect(segment)}
      aria-label={segmentAriaLabel(segment, elapsedMins)}
      aria-current={isCurrent ? 'step' : undefined}
      aria-pressed={isSelected}
      data-state={segment.state}
    >
      {body}
    </button>
  ) : (
    <div
      className={card}
      // Still announced as one thing with one name. A sealed cycle's ribbon
      // is read-only, not invisible.
      role="group"
      aria-label={segmentAriaLabel(segment, elapsedMins)}
      aria-current={isCurrent ? 'step' : undefined}
      data-state={segment.state}
    >
      {body}
    </div>
  )

  return (
    <div className="flex min-w-0 items-center" data-testid="ribbon-segment">
      {/* Its own provider rather than one shared from `App.tsx` — keeps this
          tile self-contained for Storybook and any future standalone use,
          `components/ui/tooltip.tsx`'s own note on why. */}
      <TooltipProvider delayDuration={300}>
        <Tooltip>
          <TooltipTrigger asChild>{trigger}</TooltipTrigger>
          <TooltipContent>
            <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5">
              <dt className="text-content-muted">Entered</dt>
              <dd>{tooltip.entered}</dd>
              <dt className="text-content-muted">Exited</dt>
              <dd>{tooltip.exited}</dd>
              <dt className="text-content-muted">Owner</dt>
              <dd>{tooltip.owner}</dd>
              <dt className="text-content-muted">Note</dt>
              <dd>{tooltip.note}</dd>
              <dt className="text-content-muted">Effort</dt>
              <dd>{tooltip.effort}</dd>
              <dt className="text-content-muted">Active</dt>
              <dd>{tooltip.active}</dd>
              <dt className={cn('text-content-muted', tooltip.isIdleDominated && 'font-medium text-warning-text')}>
                Idle
              </dt>
              <dd className={cn(tooltip.isIdleDominated && 'font-medium text-warning-text')}>
                {tooltip.idle}
                {/* Colour is never the only signal — the same rule the Journey
                    grid's own idle column follows for the identical reading. */}
                {tooltip.isIdleDominated && (
                  <span className="sr-only"> — most of this stage was spent waiting, not working</span>
                )}
              </dd>
            </dl>
          </TooltipContent>
        </Tooltip>
      </TooltipProvider>

      {/* The connector belongs to the segment on its left — §4A.3 describes it
          as part of that state's treatment ("filled connector to the right",
          "dashed connector"), so the strip can lay tiles out in a flex row
          without knowing anything about the pair. */}
      {!isLast && (
        <span
          aria-hidden="true"
          className={cn('mx-1 h-0.5 w-4 shrink-0 rounded-full', treatment.connector)}
          data-testid="ribbon-connector"
        />
      )}
    </div>
  )
}
