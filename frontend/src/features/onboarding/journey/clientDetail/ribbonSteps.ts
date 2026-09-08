import type { ObJourneyStepView, ObStepDot, UserRef } from '@/api/generated/model'
import type { JourneyStep, JourneyStepStatus } from '../ribbon/types'

/**
 * C-110 · the one translation between the contract's journey read and the
 * ribbon's own vocabulary.
 *
 * C-109 deliberately gave the ribbon a local `JourneyStep` and said the file
 * would be deleted "the day the contract lands". A-118 landed it, and the
 * right move turned out to be the opposite one — see `ribbon/types.ts`'s
 * header for why. What this file guarantees is that the translation happens
 * **once**: no component below reads `IN_PROGRESS`, and nothing above has to
 * know the ribbon calls it `CURRENT`.
 *
 * ## Owners are ids on the wire and names on the tile
 *
 * `ObJourneyStepView.ownerUserId` is an id, on the contract's own stated
 * convention ("one convention applied to a whole schema beats a better one
 * applied to half of it"). The tile prints a person. `resolveUser` is how the
 * page hands down the directory it already fetched, rather than this file
 * reaching for a hook and turning a pure function into a component.
 *
 * An id that resolves to nobody is **not** "Unassigned**"**: an unresolved
 * owner (C-103's null) and a user the directory page did not include are
 * different facts, and the second one silently reading as the first is how a
 * screen ends up under-reporting who is on the hook.
 */
export type ResolveUser = (userId: number) => UserRef | undefined

const STATUS: Record<string, JourneyStepStatus> = {
  PENDING: 'PENDING',
  IN_PROGRESS: 'CURRENT',
  BLOCKED: 'BLOCKED',
  WAITING_ON_CLIENT: 'WAITING',
  DONE: 'DONE',
  SKIPPED: 'SKIPPED',
}

export function ribbonStatus(status: string | undefined): JourneyStepStatus {
  return STATUS[status ?? ''] ?? 'PENDING'
}

/**
 * `dependsOnStepId` is a step **id**; the ribbon's badge prints a **sequence
 * number** (`↳ 2` means "the second service", not "row 2 of `ob_journey_steps`").
 * The whole journey's steps are the lookup table, which is why this takes the
 * set rather than one step.
 */
function dependencySeq(step: { dependsOnStepId?: number | null }, all: readonly { id: number; sequence: number }[]): number | null {
  if (step.dependsOnStepId == null) return null
  return all.find((s) => s.id === step.dependsOnStepId)?.sequence ?? null
}

/**
 * The waiver or hold sentence the tooltip shows.
 *
 * A skipped step carries `skipReason` and a blocked one `blockedNote`, and
 * they are never both set — the two states are mutually exclusive on the row.
 */
function noteFor(step: ObJourneyStepView): string | null {
  if (step.status === 'SKIPPED') return step.skipReason ?? null
  return step.blockedNote ?? null
}

/**
 * DONE steps only — §9's "on-time / early / delayed marker".
 *
 * `dueAt` is the working-calendar deadline C-105 computed; comparing it to
 * `finishedAt` is the only honest way to say which of the three happened,
 * and it is a comparison of two timestamps the server produced rather than
 * any arithmetic of our own. Absent either one, the marker is omitted — which
 * `JourneyStepClosed`'s own contract reads as "on time", the safe default for
 * a step that closed with nothing to say it was late.
 */
function closedMarker(step: ObJourneyStepView): 'early' | 'late' | null {
  if (!step.finishedAt || !step.dueAt) return null
  const finished = Date.parse(step.finishedAt)
  const due = Date.parse(step.dueAt)
  if (Number.isNaN(finished) || Number.isNaN(due)) return null
  return finished > due ? 'late' : finished < due ? 'early' : null
}

export function toRibbonStep(
  step: ObJourneyStepView,
  all: readonly ObJourneyStepView[],
  resolveUser?: ResolveUser,
): JourneyStep {
  const owner = step.ownerUserId != null ? resolveUser?.(step.ownerUserId) : undefined

  return {
    id: String(step.id),
    seqNo: step.sequence,
    name: step.name,
    status: ribbonStatus(step.status),
    owner: owner ? { displayName: owner.displayName } : null,
    // The step's own role is not on this read, so an owner we could not
    // resolve says so rather than claiming nobody holds the step.
    ownerRole: step.ownerUserId != null && !owner ? 'Assigned' : null,
    tatDays: step.tatDays ?? 0,
    // Not on this read at all — `getObJourney` returns `rag` per step and no
    // elapsed figure, so a percentage here would have to be invented. The
    // ribbon draws the breach from `rag` instead and omits the bar.
    tatPercent: null,
    rag: step.rag ?? null,
    startedOn: step.startedAt ?? null,
    finishedOn: step.finishedAt ?? null,
    closed: closedMarker(step),
    dependsOnSeqNo: dependencySeq(step, all),
    note: noteFor(step),
  }
}

export function toRibbonSteps(
  steps: readonly ObJourneyStepView[] | undefined,
  resolveUser?: ResolveUser,
): JourneyStep[] {
  const all = steps ?? []
  return all.map((step) => toRibbonStep(step, all, resolveUser))
}

/**
 * Which step the panel opens on when the reader has not picked one.
 *
 * **"The current step" is not a field, and on a real journey it is often not
 * `CURRENT`.** Northwind's seeded ERP journey is the ordinary case: two
 * services done, one BLOCKED, one WAITING_ON_CLIENT, one PENDING — and
 * nothing `IN_PROGRESS` at all. A panel that looked only for `CURRENT` would
 * open empty on exactly the journeys somebody is opening the page to ask
 * about, and would look right on the seeded data that happens to have a
 * running step.
 *
 * So the order is the one the server's own journey summary uses (`ObJourney
 * Summary.currentStep`: in progress, else blocked or waiting) with two
 * additions it does not need and a screen does — the next service on a
 * journey that has not started, and the last one on a journey that has
 * finished. A journey always has *something* worth showing.
 */
export function focusStepId(steps: readonly JourneyStep[]): string | null {
  if (steps.length === 0) return null

  const byStatus = (...wanted: JourneyStepStatus[]) =>
    steps.find((step) => wanted.includes(step.status))

  const focus =
    byStatus('CURRENT') ??
    byStatus('BLOCKED', 'WAITING') ??
    byStatus('PENDING') ??
    steps[steps.length - 1]

  return focus.id
}

/**
 * The collapsed strip's dots come from a **different, deliberately thinner**
 * schema — `ObStepDot`, which has no owner, no TAT and no notes because the
 * client portal renders the same strip and "a field the portal must never
 * show is a field that must not be in the schema it receives".
 *
 * So the dots are not ribbon tiles with fields blanked out; they are their
 * own shape, and `StepDotStrip` draws them directly. This helper exists only
 * for the label, which is the one place the two vocabularies meet.
 */
export function stepDotLabel(dot: ObStepDot): string {
  const status = ribbonStatus(dot.status)
  const words: Record<JourneyStepStatus, string> = {
    PENDING: 'Pending',
    CURRENT: 'In progress',
    DONE: 'Done',
    WAITING: 'Waiting on client',
    BLOCKED: 'Blocked',
    SKIPPED: 'Skipped',
  }
  const health = dot.rag ? ` · ${dot.rag.toLowerCase()}` : ''
  return `${dot.sequence}. ${dot.name} — ${words[status]}${health}`
}
