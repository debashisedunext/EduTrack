import type { ObClientPrereqs, ObJourneyStrip, ObRag } from '@/api/generated/model'

/**
 * C-110 · the arithmetic behind OB-05's two collapsed strips, kept out of the
 * components that draw them.
 *
 * Everything here is pure and everything here is *presentation* — the numbers
 * themselves are the server's. Onboarding-Module-Plan.md §10 is explicit that
 * `utilizedHours` is derived from `ob_step_clock_events` at read time and
 * never a stored aggregate "that can disagree with its parts"; re-deriving it
 * from the steps on the client would create exactly the second opinion that
 * sentence exists to forbid. What this file does is decide how a figure the
 * server sent is *shown*: which band it falls in, and what a reader is told
 * when it is absent.
 */

/** §5.11's amber line, default 75%. Same constant the ribbon's own
 * `DEFAULT_AMBER_THRESHOLD` carries — OB-11 makes it admin-editable and both
 * read it from the org settings then. */
export const AMBER_THRESHOLD_PERCENT = 75

/** A working day is eight working hours — the unit `WorkingHoursService`
 * computes `due_at` in, so the unit `utilizedHours` has to be divided by for
 * the strip's `used / total` to be a comparison of like with like. */
export const WORKING_HOURS_PER_DAY = 8

export type TatBand = 'ok' | 'amber' | 'over'

/**
 * `used / total` as §10 wants it drawn, or `null` when the journey has no
 * budget to spend against.
 *
 * **A journey with `totalTatDays` absent or zero is not "0% used".** A
 * template with no TATs pinned and a journey nobody has touched are different
 * facts, and a bar showing 0/0 asserts the second about the first. The strip
 * omits the figure instead, and says why in words.
 */
export interface TatUsage {
  usedDays: number
  totalDays: number
  percent: number
  band: TatBand
}

export function tatUsage(
  journey: Pick<ObJourneyStrip, 'totalTatDays' | 'utilizedHours'>,
  amberThreshold = AMBER_THRESHOLD_PERCENT,
): TatUsage | null {
  const totalDays = journey.totalTatDays ?? 0
  if (totalDays <= 0) return null

  const usedDays = (journey.utilizedHours ?? 0) / WORKING_HOURS_PER_DAY
  const percent = (usedDays / totalDays) * 100

  return {
    usedDays,
    totalDays,
    percent,
    band: percent >= 100 ? 'over' : percent >= amberThreshold ? 'amber' : 'ok',
  }
}

/**
 * One decimal, and the trailing `.0` dropped.
 *
 * 19 hours against a 3-day budget is 2.4 days; printing 2 would round a
 * quarter of the overrun away, and printing 2.375 would claim a precision
 * `utilizedHours` does not have (the mock and the server both round it to one
 * decimal before it leaves).
 */
export function formatDays(days: number): string {
  const rounded = Math.round(days * 10) / 10
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1)
}

export function formatTatUsage(usage: TatUsage): string {
  return `${formatDays(usage.usedDays)} / ${formatDays(usage.totalDays)}d TAT`
}

/**
 * What the strip says instead of a percentage when there is nothing to show
 * one for, and what the accordion says beside a journey that is not running.
 *
 * Three different reasons a journey shows no progress, and OB-03's own list
 * already refuses to merge them (`ObRag` "carries health and nothing else").
 * The same refusal applies here: a locked gate, a held sibling and a finished
 * journey all draw no colour, and telling a reader which is which is the
 * difference between a screen they trust and one they file a bug about.
 */
export type JourneyHold = 'GATE_LOCKED' | 'HELD_BY_SIBLING' | null

export function journeyHold(journey: Pick<ObJourneyStrip, 'gateStatus' | 'heldByJourneyId'>): JourneyHold {
  // The gate first: a locked journey is locked whatever else is true of it,
  // and it is the one a reader can act on (clear the prerequisites above).
  if (journey.gateStatus === 'LOCKED') return 'GATE_LOCKED'
  if (journey.heldByJourneyId != null) return 'HELD_BY_SIBLING'
  return null
}

/** Chip variants, as `components/ui/chip.tsx` names them. RAG is the only
 * thing here that is a colour, and it is the server's colour. */
export function ragVariant(rag: ObRag | null | undefined): 'success' | 'warning' | 'danger' | 'neutral' {
  if (rag === 'GREEN') return 'success'
  if (rag === 'AMBER') return 'warning'
  if (rag === 'RED') return 'danger'
  return 'neutral'
}

export function ragLabel(rag: ObRag | null | undefined): string {
  if (rag === 'GREEN') return 'On track'
  if (rag === 'AMBER') return 'At risk'
  if (rag === 'RED') return 'Breached'
  return 'Not started'
}

/**
 * Which journey opens itself, the way the mockup's `vClient` does.
 *
 * `A.selJourney` picks "the first journey that is running and unfinished, else
 * the first", expands it and selects a step — so the prototype never draws a
 * ribbonless page. A product page that opened on a row of collapsed strips
 * would be hiding the thing the screen is for.
 *
 * "Running" is past the gate, not held behind a sibling, and unfinished. A
 * client whose first service is complete and whose second is mid-flight should
 * open on the one somebody still has work in — and when nothing is running
 * (everything finished, everything locked) the first strip opens anyway,
 * because a page with a ribbon on it beats a page without one.
 */
export function defaultOpenJourneyId(
  journeys: readonly Pick<ObJourneyStrip, 'id' | 'gateStatus' | 'heldByJourneyId' | 'percentComplete'>[],
): number | undefined {
  if (journeys.length === 0) return undefined
  const running = journeys.find(
    (j) => j.gateStatus !== 'LOCKED' && j.heldByJourneyId == null && (j.percentComplete ?? 0) < 100,
  )
  return (running ?? journeys[0]).id
}

/**
 * The prerequisites strip's own progress, §9's "gate chip + mandatory-progress
 * bar".
 *
 * `optionalOutstanding` is carried alongside rather than folded into the bar
 * for the reason `ObClientPrereqs` states on the field itself: the bar counts
 * mandatory tasks only, so "4 / 4 mandatory" beside a locked gate looks broken
 * unless the screen can also say that two optional ones are still open.
 */
export interface PrereqProgress {
  verified: number
  total: number
  percent: number
  optionalOutstanding: number
  isCleared: boolean
}

export function prereqProgress(prereqs: Pick<ObClientPrereqs, 'mandatoryTotal' | 'mandatoryVerified' | 'optionalOutstanding' | 'gateStatus'>): PrereqProgress {
  const total = prereqs.mandatoryTotal ?? 0
  const verified = prereqs.mandatoryVerified ?? 0
  return {
    verified,
    total,
    // A checklist with no mandatory tasks is complete, not empty. The gate
    // still turns on the optional ones being settled, which is why `percent`
    // is not what `isCleared` is read from.
    percent: total <= 0 ? 100 : Math.round((verified / total) * 100),
    optionalOutstanding: prereqs.optionalOutstanding ?? 0,
    isCleared: prereqs.gateStatus === 'OPEN',
  }
}
