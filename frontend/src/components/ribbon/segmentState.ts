import { CircleDashed, Check, PauseCircle, RotateCcw, SkipForward, Loader } from 'lucide-react'
import { format, parseISO } from 'date-fns'

import type { RibbonSegment } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { ROLE_LABEL } from '@/lib/roleLabel'
import { formatDuration, formatEffortHrs } from '@/lib/duration'

/**
 * B-050 · the six segment states of blueprint §4A.3, as data rather than as a
 * chain of ternaries inside the component.
 *
 * Separated so the part that has to be *right* — which state gets which words,
 * and what a screen reader is told — can be tested without a DOM, and so
 * B-051's compact dot and B-053's collapsed group read the same vocabulary
 * instead of inventing a second one three weeks from now.
 *
 * ## Colour is never the only signal
 *
 * `styles/tokens.css` says so in the ribbon block's own header, and §17's
 * accessibility item and CLAUDE.md's WCAG AA line both require it. Every state
 * therefore carries an **icon and a word**, and the colour is the third
 * channel rather than the first. A ribbon that distinguishes "done" from
 * "blocked" by green against grey is unreadable to a colour-blind reader, and
 * §4A.3's own state table pairs every colour with a shape for that reason.
 *
 * ## Two states have no token, and none was requested
 *
 * `tokens.css` ships `--ribbon-done|current|pending|reworked|breached` and
 * nothing for skipped or blocked. That file is Stream C's and frozen after
 * Sprint 0 — "others request, never add" — and neither state needs a colour it
 * does not already have: §4A.3 describes skipped as a *dashed outline with a
 * struck-through label* and blocked as *grey with a pause icon*, which are the
 * pending greys plus a border style and an icon. Both map onto
 * `--ribbon-pending-*` and the distinction is carried by shape and word, which
 * is where §4A.3 put it. Nothing here is waiting on a token.
 *
 * `--ribbon-breached` is deliberately unused: an SLA breach is a fact about a
 * stage's clock, not a segment state, it is absent from `SegmentState`, and
 * `RibbonSegment` carries no breach flag to render it from. When D's stage-SLA
 * scanner surfaces one it is an overlay on top of a state, not a seventh one.
 */

/** What one state looks like and what it is called. */
export interface SegmentTreatment {
  /** Lucide icon for the state itself — §4A.3's "stage name + state icon". */
  Icon: typeof Check
  /** The word beside the icon, and the word a screen reader hears. */
  label: string
  /** Card surface, border and text. Tokens only — never a raw colour. */
  card: string
  /** The stage-name text, so `SKIPPED` can strike itself through. */
  title: string
  /** The connector drawn to this segment's right, before the next one. */
  connector: string
}

export const SEGMENT_TREATMENT: Record<SegmentState, SegmentTreatment> = {
  // Solid tick, green accent, filled connector to the right.
  COMPLETED: {
    Icon: Check,
    label: 'Completed',
    card: 'border-ribbon-done bg-ribbon-done-bg text-ribbon-done-text',
    title: 'text-ribbon-done-text',
    connector: 'bg-ribbon-done',
  },
  // Indigo fill, pulse ring, "Now" label. The elapsed timer is the component's,
  // because it ticks and this file is pure.
  CURRENT: {
    Icon: Loader,
    label: 'Now',
    // `ring-2 ring-ribbon-current` and not `ring-ribbon-current/30`: the ribbon
    // tokens are `var(--…)` strings, and Tailwind 3's opacity modifier cannot
    // compute a channel from one — it emits a declaration the browser drops, so
    // the "subtle" in §4A.3's "subtle pulse ring" would arrive as no ring at
    // all. Subtlety comes from the pulse and the 2px width instead.
    card: 'border-ribbon-current bg-ribbon-current-bg text-ribbon-current-text ring-2 ring-ribbon-current',
    title: 'text-ribbon-current-text',
    connector: 'bg-ribbon-pending',
  },
  // Outlined, muted grey, dashed connector.
  PENDING: {
    Icon: CircleDashed,
    label: 'Pending',
    card: 'border-ribbon-pending bg-ribbon-pending-bg text-ribbon-pending-text',
    title: 'text-ribbon-pending-text',
    connector: 'border-t-2 border-dashed border-ribbon-pending',
  },
  // Amber left edge. The loop-back badge is drawn from `loopBackCount` and not
  // from this state — a segment can have bounced twice and be the current one
  // again, and only one of those two facts is its state.
  REWORKED: {
    Icon: RotateCcw,
    label: 'Reworked',
    card: 'border-ribbon-pending bg-ribbon-pending-bg text-ribbon-reworked-text border-l-4 border-l-ribbon-reworked',
    title: 'text-ribbon-reworked-text',
    connector: 'bg-ribbon-reworked',
  },
  // Dashed outline, strikethrough label, reason on hover.
  SKIPPED: {
    Icon: SkipForward,
    label: 'Skipped',
    card: 'border-dashed border-ribbon-pending bg-ribbon-pending-bg text-ribbon-pending-text',
    title: 'text-ribbon-pending-text line-through',
    connector: 'border-t-2 border-dashed border-ribbon-pending',
  },
  // Grey with a pause icon. The hold reason has nowhere to come from — see the
  // README in this directory.
  BLOCKED: {
    Icon: PauseCircle,
    label: 'On hold',
    card: 'border-ribbon-pending bg-subtle text-ribbon-pending-text',
    title: 'text-ribbon-pending-text',
    connector: 'border-t-2 border-dashed border-ribbon-pending',
  },
}

/**
 * An unknown state is `PENDING`, not a crash.
 *
 * `SegmentState` is a wire enum, so a server that grows a seventh state ships
 * it to a client built against six. Rendering the most neutral of the six is
 * the only option that neither throws inside a list nor makes a claim: a
 * segment whose state this client cannot read has, as far as it knows, not
 * happened yet.
 */
export function treatmentFor(state: SegmentState | undefined): SegmentTreatment {
  return SEGMENT_TREATMENT[state as SegmentState] ?? SEGMENT_TREATMENT[SegmentState.PENDING]
}

/**
 * Who is on this segment, in the words the tile and the ARIA label both use.
 *
 * A pending stage has no owner — nobody has held it — so it names the role
 * that will. Collapsing both cases to "Unassigned" would flatten two different
 * facts, "waiting for the QA team" and "this stage has nobody on it", into one
 * word; the second is an alert and the first is normal.
 */
export function ownerLabel(segment: RibbonSegment): string {
  if (segment.owner?.displayName) return segment.owner.displayName
  if (segment.ownerRole) return `${ROLE_LABEL[segment.ownerRole]} · unassigned`
  return 'Unassigned'
}

/**
 * §4A.3: "each segment has an ARIA label reading the stage, owner, state and
 * effort". Those four in that order, plus the loop count when there is one — a
 * segment that has bounced twice is the single most important thing on the
 * ribbon, and a sighted reader gets it from a badge.
 *
 * Duration is in there as well, between state and effort. "Development, Ravi
 * Kumar, completed, 14.5 h effort" leaves a screen-reader user unable to see
 * the queue problem §4A.4 exists to surface; the tile shows both numbers, so
 * this says both numbers.
 *
 * B-052 owns keyboard navigation across the strip. This is the per-segment
 * accessible name, which belongs to the segment.
 */
export function segmentAriaLabel(segment: RibbonSegment, elapsedMins?: number | null): string {
  const treatment = treatmentFor(segment.state)
  const stage = segment.displayName ?? segment.stageCode ?? 'Stage'
  const duration = elapsedMins != null ? formatDuration(elapsedMins) : formatDuration(segment.durationMins)

  const parts = [
    stage,
    ownerLabel(segment),
    segment.state === SegmentState.CURRENT ? 'current stage' : treatment.label.toLowerCase(),
    `${duration} in stage`,
    `${formatEffortHrs(segment.effortHrs)} effort`,
  ]

  if (segment.loopBackCount && segment.loopBackCount > 0) {
    parts.push(`returned ${segment.loopBackCount} ${segment.loopBackCount === 1 ? 'time' : 'times'}`)
  }
  if (segment.state === SegmentState.SKIPPED && segment.skipReason) {
    parts.push(`skipped because ${segment.skipReason}`)
  }

  return parts.join(', ')
}

const TOOLTIP_TIMESTAMP_FORMAT = 'd MMM yyyy, HH:mm'

function formatTimestamp(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = parseISO(value)
  if (Number.isNaN(parsed.getTime())) return '—'
  return format(parsed, TOOLTIP_TIMESTAMP_FORMAT)
}

/** C-052 · the rich hover tooltip's content, structured rather than one string
 * so `RibbonSegment` can lay it out as a definition list. Pure and tested
 * without a DOM, the same reason `segmentAriaLabel` lives here. */
export interface SegmentTooltipDetails {
  entered: string
  exited: string
  owner: string
  note: string
  effort: string
  active: string
  idle: string
  /** Idle at or past half of duration — the tile's own reading aid. */
  isIdleDominated: boolean
}

/**
 * §4A.3's hover: "entered/exited/owner/note/effort/idle-vs-active". Six
 * fields, all formatted here so `RibbonSegment` only ever renders strings.
 *
 * **Its own idle-vs-active threshold, not `journeyFormat.ts`'s `isQueueBound`.**
 * `journeyFormat.ts`'s own note names this file as the place that split
 * belongs, rather than importing the Journey grid's copy — `journey/` is a
 * feature and `components/ribbon/` is shared, and `lib/duration.ts`'s header
 * already declines that import direction for the same reason. The 50%
 * threshold is repeated rather than shared, on the same number and the same
 * blueprint example ("2 days duration, 2 hours effort") the grid used to
 * choose it, so a stage read as queue-bound in one place reads the same way
 * in the other.
 */
export function segmentTooltipDetails(segment: RibbonSegment): SegmentTooltipDetails {
  const isCurrent = segment.state === SegmentState.CURRENT
  const { durationMins, idleMins } = segment
  const measured = durationMins != null && idleMins != null

  return {
    entered: formatTimestamp(segment.enteredAt),
    exited: segment.exitedAt ? formatTimestamp(segment.exitedAt) : isCurrent ? 'Still open' : '—',
    owner: ownerLabel(segment),
    note:
      segment.state === SegmentState.SKIPPED && segment.skipReason
        ? `Skipped: ${segment.skipReason}`
        : (segment.handoffNote ?? '—'),
    effort: formatEffortHrs(segment.effortHrs),
    active: measured ? formatDuration(Math.max(durationMins - idleMins, 0)) : isCurrent ? 'Not yet measured' : '—',
    idle: measured ? formatDuration(idleMins) : isCurrent ? 'Not yet measured' : '—',
    isIdleDominated: measured && durationMins > 0 && idleMins / durationMins >= 0.5,
  }
}

/**
 * §4A.3's inline action label, `Hand off to QA →` — the next stage's name,
 * read from the segment straight after the current one rather than invented.
 * `undefined` (the last stage, or an index the strip could not resolve) falls
 * back to the plain verb.
 */
export function nextStageLabel(segments: RibbonSegment[], currentIndex: number): string {
  const next = segments[currentIndex + 1]
  const name = next?.displayName ?? next?.stageCode
  return name ? `Hand off to ${name} →` : 'Hand off →'
}
