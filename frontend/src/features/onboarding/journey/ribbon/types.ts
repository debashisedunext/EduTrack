/**
 * C-109 · the onboarding journey ribbon's own wire shape.
 *
 * **Still local after A-101 landed, and now for a different reason.** C-109
 * wrote this file because `frontend/src/api/generated/` had no onboarding
 * models at all; it does now, and `ObJourneyStepView` is the read the ribbon
 * is fed from. It is not the shape the ribbon draws:
 *
 * - `ObJourneyStepView.status` carries `IN_PROGRESS`/`WAITING_ON_CLIENT`,
 *   which are server words for `CURRENT`/`WAITING` here — the tile's own
 *   vocabulary, and the one `stepState.ts` keys its treatments on.
 * - `ownerUserId` is an id. The tile prints a name, which is a second read.
 * - `tatPercent` is not in that schema at all (`ObJourneyStepDetail`'s
 *   `elapsedHours` is one step at a time), so the ribbon has to be able to
 *   draw a breach from `rag` alone.
 *
 * So this stays as the ribbon's own vocabulary and C-110's `ribbonSteps.ts`
 * is the one adapter between the two. One translation in one file beats a
 * generated model half-translated at every call site.
 */

/** §5.7's step states. `CURRENT`/`WAITING` are `IN_PROGRESS`/
 * `WAITING_ON_CLIENT` in `ObJourneyStepStatus` — see the header.
 *
 * **`SKIPPED` is C-110's addition, and it is a correction rather than a new
 * feature.** C-109 wrote "onboarding has no SKIPPED equivalent" against the
 * five states its Storybook fixtures used; C-107 had already shipped the skip
 * transition, and `ObJourneyStepStatus` carries `SKIPPED`. Folding it into
 * `DONE` would have the ribbon tell a reader that a waived service was
 * delivered — on the screen sign-off is decided from. */
export type JourneyStepStatus = 'PENDING' | 'CURRENT' | 'DONE' | 'WAITING' | 'BLOCKED' | 'SKIPPED'

/** DONE only. `null`/absent reads as "closed on time" — the prototype's own
 * `stEmoji` treats a missing `closed` the same way. */
export type JourneyStepClosed = 'early' | 'late' | null

/** §5.9's health colour, as the server computed it (C-114). `null` where
 * there is nothing running to colour. */
export type JourneyStepRag = 'GREEN' | 'AMBER' | 'RED' | null

export interface JourneyStepOwner {
  displayName: string
  avatarUrl?: string | null
}

export interface JourneyStep {
  /** Stable id — a step's own row, not the journey's. */
  id: string
  /** 1-based position in the template, the number the tile prints (`§9` OB-05: "1. Kickoff call"). */
  seqNo: number
  name: string
  status: JourneyStepStatus
  owner?: JourneyStepOwner | null
  /** Set when `owner` is not — a step nobody has picked up yet still names who will. */
  ownerRole?: string | null
  /** §5.10 — this step's own TAT budget, working days, from the pinned template version. */
  tatDays: number
  /** Working hours consumed vs `tatDays`, 0-100+, `null` before the step has started.
   * Server-computed from `ob_step_clock_events` at read time (§5.10) — never derived here. */
  tatPercent: number | null
  /** The server's own health colour for this step, when the read carries one.
   *
   * **Authoritative over `tatPercent` where the two could disagree.** C-114
   * computes RAG on the working calendar with the org's amber threshold;
   * anything this file derived from a percentage would be a second opinion
   * about the same fact. It also lets the journey read — which has `rag` and
   * no percent — still draw a breach, which is the case OB-05 actually has.
   */
  rag?: JourneyStepRag
  /** DONE only — start/finish dates, §9 OB-05's "SD/FD with an on-time / early / delayed marker". */
  startedOn?: string | null
  finishedOn?: string | null
  closed?: JourneyStepClosed
  /** §5.6 — the earlier step this one depends on, `null`/absent for a
   * dependency-free step that runs in parallel with its siblings. 1-based,
   * matching `seqNo`. */
  dependsOnSeqNo?: number | null
  /** §5.8's task-list gate — sub-categories answered vs total. */
  subTasksAnswered?: number
  subTasksTotal?: number
  /** Hold reason (BLOCKED), or the waiver reason (SKIPPED) — §4A.3's
   * precedent for the phase-1 ribbon. */
  note?: string | null
}
