/**
 * C-109 · the onboarding journey ribbon's own wire shape.
 *
 * `frontend/src/api/generated/` has no onboarding models yet — A-101 (the
 * OpenAPI contract, PHASE-2-BUILD-PLAN.md §6.0) is still blocked behind
 * A-103/A-104/A-105, which this task's own dependency graph does not wait on.
 * `HandoffDialog`'s own precedent (`ribbon?: Ribbon`, an optional prop typed
 * against a shape the real service did not fill in yet) is the one this
 * follows: build against a local type now, delete this file and re-point the
 * two imports at the generated model the day the contract lands. Nothing else
 * in this directory changes shape when that happens — `JourneyStep` below is
 * written to already match Onboarding-Module-Plan.md §5/§9 field for field.
 */

/** §5.7's five step states. Onboarding has no REWORKED/SKIPPED equivalent —
 * a step is either worked, waiting, blocked, or hasn't started. */
export type JourneyStepStatus = 'PENDING' | 'CURRENT' | 'DONE' | 'WAITING' | 'BLOCKED'

/** DONE only. `null`/absent reads as "closed on time" — the prototype's own
 * `stEmoji` treats a missing `closed` the same way. */
export type JourneyStepClosed = 'early' | 'late' | null

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
  /** Hold reason (BLOCKED) — the hover card's note, §4A.3's precedent for the phase-1 ribbon. */
  note?: string | null
}
