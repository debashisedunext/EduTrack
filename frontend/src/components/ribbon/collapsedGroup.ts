import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'

/**
 * B-053 · blueprint §17's mitigation for "ribbon becomes unreadable at 8
 * stages on a laptop" is two behaviours, and this file is the first —
 * "collapsed '…' grouping for completed stages beyond the first three".
 *
 * Split pure from stateful for the same reason `rovingFocus.ts` is:
 * `buildRibbonRows` is where "which stages are beyond the first three" is
 * either right or wrong, and it is testable without a DOM. `RibbonStrip`
 * only owns the one bit of state a reader can change — which groups are
 * expanded — and hands both back here every render.
 *
 * ## Only `COMPLETED` collapses
 *
 * `segmentState.ts`'s own header asks for it: "B-051's compact dot and
 * B-053's collapsed group read the same vocabulary instead of inventing a
 * second one." `REWORKED`, `SKIPPED` and `BLOCKED` all carry something a
 * completed tile does not — a loop badge, a reason, a hold — and folding
 * them into "…" would be the summary hiding the one thing worth reading on
 * the ribbon. So a run breaks the moment a non-`COMPLETED` segment sits in
 * it, rather than being "every stage before the first three plus three".
 *
 * ## Collapsing does not remove the segment, only its own tile
 *
 * B-052 made every tile on the strip reachable, including the read-only
 * ones — a group that quietly dropped a stage from the DOM would undo that
 * for whichever stage a reader most wants to click on to filter History and
 * Effort below. So a collapsed run is still there, in `group.segments`;
 * `expandedKeys` is the only thing deciding whether it renders as one tile
 * or as itself.
 *
 * ## Only stages before the current one are ever counted
 *
 * `CURRENT` is the leading edge of a ticket's progress, so a `COMPLETED`
 * segment at or after it is not a real history — nothing in the backend or
 * the mock produces one, and folding a stage the ticket has not reached yet
 * into a "…" group would be inventing history rather than condensing it.
 * The bound also happens to be what makes a *sealed* ribbon — every segment
 * `COMPLETED`, no `CURRENT` at all — collapse from the start rather than not
 * at all: with no current segment the bound is the whole list, which is the
 * one case this rule and "collapse everything past the third completed
 * stage" agree exactly.
 */

export interface SegmentRow {
  kind: 'segment'
  segment: RibbonSegmentData
}

export interface GroupRow {
  kind: 'group'
  /** Stable across renders as long as the run's first stage and length do — see `groupKey`. */
  key: string
  segments: RibbonSegmentData[]
  expanded: boolean
}

export type RibbonRow = SegmentRow | GroupRow

/** The first three `COMPLETED` segments, wherever they fall, stay individual tiles. */
const VISIBLE_COMPLETED_COUNT = 3

/**
 * A run's key is its first segment plus its length, not its position in the
 * strip — a live `stage.changed` frame can insert a new current segment
 * ahead of a completed run without changing what that run *is*, and a key
 * keyed on index would collapse an expanded group back the next time the
 * ribbon re-renders.
 */
function groupKey(run: RibbonSegmentData[]): string {
  const first = run[0]
  return `${first?.stageCode ?? ''}:${first?.iterationNo ?? 1}:${run.length}`
}

export function buildRibbonRows(
  segments: RibbonSegmentData[],
  expandedKeys: ReadonlySet<string> = new Set(),
): RibbonRow[] {
  const currentIndex = segments.findIndex((segment) => segment.state === SegmentState.CURRENT)
  const bound = currentIndex === -1 ? segments.length : currentIndex

  const rows: RibbonRow[] = []
  let completedSeen = 0
  let i = 0

  while (i < segments.length) {
    const segment = segments[i]
    const eligible = i < bound && segment.state === SegmentState.COMPLETED
    if (!eligible) {
      rows.push({ kind: 'segment', segment })
      i++
      continue
    }

    completedSeen++
    if (completedSeen <= VISIBLE_COMPLETED_COUNT) {
      rows.push({ kind: 'segment', segment })
      i++
      continue
    }

    const start = i
    while (i < bound && segments[i].state === SegmentState.COMPLETED) {
      completedSeen++
      i++
    }
    const run = segments.slice(start, i)
    const key = groupKey(run)
    const expanded = expandedKeys.has(key)
    rows.push({ kind: 'group', key, segments: run, expanded })
    if (expanded) {
      run.forEach((runSegment) => rows.push({ kind: 'segment', segment: runSegment }))
    }
  }

  return rows
}

/**
 * §4A.3's per-segment ARIA label reads stage, owner, state and effort;
 * a group speaks for several segments at once, so it names them instead —
 * the words a screen reader needs to decide whether expanding is worth it.
 */
export function collapsedGroupAriaLabel(segments: RibbonSegmentData[], expanded: boolean): string {
  const names = segments.map((segment) => segment.displayName ?? segment.stageCode ?? 'Stage').join(', ')
  const count = segments.length
  const noun = count === 1 ? 'stage' : 'stages'
  return expanded
    ? `Collapse ${count} completed ${noun}: ${names}`
    : `${count} completed ${noun} collapsed: ${names}`
}
