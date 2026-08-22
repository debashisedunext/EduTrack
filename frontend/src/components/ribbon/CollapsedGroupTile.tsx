import * as React from 'react'
import { ChevronsLeftRight, MoreHorizontal } from 'lucide-react'

import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { cn } from '@/lib/utils'
import { collapsedGroupAriaLabel } from './collapsedGroup'
import { treatmentFor } from './segmentState'

/**
 * B-053 · the "…" tile blueprint §17 asks for — a run of completed stages
 * beyond the first three, folded into one control rather than one tile each.
 *
 * A single toggle rather than a pair of them: this tile sits where the run's
 * first segment would, and its own label and `aria-expanded` are what flip
 * between "N completed stages collapsed" and "Collapse N completed stages" —
 * `RibbonStrip` renders the run's real segments right after it when expanded,
 * so there is never a second control to find and no separate "show fewer"
 * tile trailing the run to keep in sync with this one.
 *
 * Always a `<button>`, never a `<div role="group">` — unlike `RibbonSegment`,
 * whose control-ness depends on the caller wiring `onSelect`. Expanding a
 * group changes what is on screen, not what the ribbon means, so a sealed
 * past cycle's read-only strip can still fold and unfold its own completed
 * run even though none of its tiles are selectable.
 *
 * Styled from `treatmentFor(COMPLETED)` rather than a colour of its own —
 * every segment inside is a completed one, and `segmentState.ts`'s own
 * header asks this file to share that vocabulary rather than invent a
 * second one for the same six states.
 */
export interface CollapsedGroupTileProps {
  segments: RibbonSegmentData[]
  expanded: boolean
  onToggle: () => void
  /** Suppresses the trailing connector — the strip sets it on the last row. */
  isLast?: boolean
  /** `0` for the roving tab stop, `-1` otherwise — the same contract `RibbonSegment` takes from `useRovingFocus`. */
  tabIndex?: number
  onFocus?: () => void
  className?: string
}

export const CollapsedGroupTile = React.forwardRef<HTMLButtonElement, CollapsedGroupTileProps>(
  function CollapsedGroupTile({ segments, expanded, onToggle, isLast = false, tabIndex, onFocus, className }, ref) {
    const treatment = treatmentFor(SegmentState.COMPLETED)
    const label = collapsedGroupAriaLabel(segments, expanded)

    return (
      <div className="flex min-w-0 items-center" data-testid="ribbon-collapsed-group">
        <button
          ref={ref}
          type="button"
          tabIndex={tabIndex}
          onFocus={onFocus}
          onClick={onToggle}
          aria-expanded={expanded}
          aria-label={label}
          className={cn(
            'flex w-14 shrink-0 flex-col items-center justify-center gap-0.5 self-stretch rounded-card border p-2 text-center shadow-rest transition',
            'hover:shadow-modal focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1',
            treatment.card,
            className,
          )}
        >
          {expanded ? (
            <ChevronsLeftRight className="h-4 w-4 shrink-0" aria-hidden="true" />
          ) : (
            <>
              <MoreHorizontal className="h-4 w-4 shrink-0" aria-hidden="true" />
              <span className="text-xs font-semibold tabular-nums">+{segments.length}</span>
            </>
          )}
        </button>

        {/* Same treatment as a completed segment's own connector — the group
            stands in for a run of them, not a seventh state. */}
        {!isLast && (
          <span
            aria-hidden="true"
            className={cn('mx-1 h-0.5 w-4 shrink-0 rounded-full', treatment.connector)}
            data-testid="ribbon-connector"
          />
        )}
      </div>
    )
  },
)
