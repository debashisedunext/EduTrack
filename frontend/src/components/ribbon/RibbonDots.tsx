import { SegmentState } from '@/api/generated/model/segmentState'
import { cn } from '@/lib/utils'
import { type CompactDot, compactDotsAriaLabel, dotTitle } from './compactDots'

/**
 * B-051 · the compact ribbon — blueprint line 307 and line 984, and the
 * "Journey" column of `docs/prototype/index.html`.
 *
 * Eight small dots standing in for the eight tiles `RibbonSegment` draws, so a
 * manager scanning S-17 sees where every ticket sits without opening one. The
 * two components share `SegmentState` and nothing else: a tile is a card with
 * five data points and a click, and a dot is a 7px mark in a grid cell that
 * cannot carry any of them.
 *
 * ## Shape carries the state, and colour repeats it
 *
 * Not the other way round. `tokens.css`'s ribbon block and CLAUDE.md both
 * require a second channel, and at this size icon and word are unavailable —
 * so completed is a filled circle, current is a larger filled circle inside a
 * ring, pending is a hollow outline, and reworked is a diamond. Four marks
 * that stay apart in greyscale.
 *
 * The prototype's own CSS (`.dots i`, `.f`, `.c`, `.w`) is 7px circles with a
 * `box-shadow` ring on the current one; this keeps its geometry and adds the
 * diamond, which the prototype did not need because it drew one hardcoded row.
 *
 * ## One accessible name for the cell, and a `title` per dot
 *
 * Eight focusable marks × 25 rows is 200 stops between a keyboard reader and
 * the bottom of the page, for a cell with nothing to activate. So the strip is
 * a single `role="img"` with one sentence — see `compactDotsAriaLabel` — and
 * the per-dot naming §S-17 asks for ("Hovering a dot names the stage and its
 * owner") is a native `title`, which costs nothing per row.
 *
 * A Radix tooltip is what `RibbonSegment` uses and is deliberately **not**
 * used here: it is the right call for one ribbon of eight tiles on a detail
 * page, and 200 tooltip roots in a grid is a different question with a
 * different answer.
 */
export function RibbonDots({ dots, className }: { dots: CompactDot[]; className?: string }) {
  return (
    <div
      role="img"
      aria-label={compactDotsAriaLabel(dots)}
      className={cn('flex items-center gap-[3px]', className)}
    >
      {dots.map((dot, index) => (
        <span
          // `stageCode` is unique within a template, so it is a stable key —
          // the index is the tiebreak for a payload that somehow repeats one.
          key={`${dot.stageCode}-${index}`}
          title={dotTitle(dot)}
          data-state={dot.state}
          data-stage={dot.stageCode}
          // The name is already on the parent `img`; announcing it eight more
          // times per row is the thing that name exists to avoid.
          aria-hidden
          className={cn('block flex-none', DOT_CLASS[dot.state] ?? DOT_CLASS[SegmentState.PENDING])}
        />
      ))}
    </div>
  )
}

/**
 * Four marks, four token colours, four shapes.
 *
 * `ring-2 ring-ribbon-current` and not `ring-ribbon-current/40`: the ribbon
 * tokens are `var(--…)` strings and Tailwind 3's opacity modifier cannot
 * compute a channel from one — it emits a declaration the browser drops, so
 * the ring would arrive as no ring at all. `segmentState.ts` hit the same wall
 * on the current tile and records it there.
 *
 * An unknown state falls to pending, the neutral degradation `treatmentFor`
 * makes for the same reason: a state this client cannot read has, as far as it
 * knows, not happened yet.
 */
const DOT_CLASS: Partial<Record<SegmentState, string>> = {
  // Filled circle.
  [SegmentState.COMPLETED]: 'h-[7px] w-[7px] rounded-full bg-ribbon-done',
  // Larger filled circle inside a ring — the prototype's `box-shadow` halo.
  [SegmentState.CURRENT]: 'h-[7px] w-[7px] rounded-full bg-ribbon-current ring-2 ring-ribbon-current-bg',
  // Hollow outline.
  [SegmentState.PENDING]: 'h-[7px] w-[7px] rounded-full border border-ribbon-pending bg-transparent',
  // Diamond — a filled mark rotated 45°, so "sent back" survives greyscale.
  [SegmentState.REWORKED]: 'h-[7px] w-[7px] rotate-45 rounded-[1px] bg-ribbon-reworked',
}
