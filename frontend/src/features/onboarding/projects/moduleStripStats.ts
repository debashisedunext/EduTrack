import type { ObJourneyStepItem } from '@/api/generated/model/obJourneyStepItem'

import type { TreeService, TreeStage } from './projectTree'
import type { ProjectTask } from './useProjectTasks'

/**
 * How much of this work is done, and whose it is.
 *
 * <p>Everything the Module strip prints and everything a ribbon segment prints
 * is derived here, so the strip and the segments under it cannot disagree about
 * what "partial" means. Pure over data the page already fetched — see
 * `projectTree.ts` for why none of this is a request.
 *
 * <h2>Three buckets, and they are exclusive</h2>
 *
 * <p>Pending, partially completed, completed. They sum to the total, which is
 * what makes the strip checkable at a glance — a reader who adds them up and
 * lands somewhere other than the total has found a bug, and that is worth
 * preserving.
 */

/** Where one task sits, in the strip's own three words. */
export type TaskProgress = 'PENDING' | 'PARTIAL' | 'COMPLETED'

/**
 * The statuses that mean work has actually started.
 *
 * <p>`BLOCKED` and `WAITING_ON_CLIENT` are in here because both are states a
 * task reaches by being <em>worked on</em> — somebody started it and hit
 * something. Reporting either as "not started" would put a task waiting on a
 * client since the 11th in the same bucket as one nobody has opened.
 *
 * <p>`PENDING_REVIEW` is in here for the same reason and is the clearest case
 * of it: the implementor has finished, and the only thing outstanding is
 * somebody else reading it. It is emphatically <b>not</b> settled — see
 * {@link isSettledStatus} — because a Step that counted an unread task as done
 * would report work complete that nobody has checked, which is the one thing
 * the review gate exists to prevent.
 */
const STARTED = new Set(['IN_PROGRESS', 'BLOCKED', 'WAITING_ON_CLIENT', 'PENDING_REVIEW'])

/**
 * The same reading over a bare status, for a row that is not a
 * {@link ProjectTask} — My Tasks' queue carries the status without the check
 * list a `ProjectTask` brings. One definition so the two screens cannot
 * disagree about which statuses mean work has started.
 */
export function isStartedStatus(status: string): boolean {
  return STARTED.has(status)
}

/**
 * Answered, either way.
 *
 * <p>`isDone` is the server's `answer IS NOT NULL` — an item answered "False,
 * and here is why" is progress and satisfies the completion gate exactly as a
 * True does. `answer != null` is read as well as the flag because
 * `ObProjectStageBody` counts the row's own checklist pill that way, and the
 * two readings of one check list must not differ by a component.
 */
function isAnswered(item: ObJourneyStepItem): boolean {
  return item.isDone || item.answer != null
}

/**
 * Settled — `DONE` or `SKIPPED`, the same reading `tasksOutstanding` uses
 * server-side. A waived task is not outstanding work.
 */
export function isSettled(task: ProjectTask): boolean {
  return isSettledStatus(task.status)
}

/** The same reading over a bare status — see {@link isStartedStatus}. */
export function isSettledStatus(status: string): boolean {
  return status === 'DONE' || status === 'SKIPPED'
}

/**
 * One task's bucket.
 *
 * <p><b>Partial takes either signal</b> — a live status, or any answered check
 * list item. Counting only the status would report a task somebody has been
 * ticking through all morning, but never moved off `PENDING`, as untouched;
 * counting only the check list would report a task in progress with nothing to
 * tick as untouched. A person looking at the row would call both partial.
 */
export function taskProgress(task: ProjectTask): TaskProgress {
  if (isSettled(task)) return 'COMPLETED'
  if (isStartedStatus(task.status)) return 'PARTIAL'
  return task.items.some(isAnswered) ? 'PARTIAL' : 'PENDING'
}

export interface StripStats {
  pending: number
  partial: number
  completed: number
  total: number
  /**
   * Completed over total, rounded, 0–100.
   *
   * <p>Partial is deliberately <b>not</b> counted as half a task. It shows as
   * the hatched part of the meter instead — a percentage that silently credits
   * half of something nobody has finished is how a project reads as 60% done on
   * the day it slips.
   */
  percent: number
}

export function stripStats(tasks: readonly ProjectTask[]): StripStats {
  let pending = 0
  let partial = 0
  let completed = 0

  tasks.forEach((task) => {
    const bucket = taskProgress(task)
    if (bucket === 'COMPLETED') completed += 1
    else if (bucket === 'PARTIAL') partial += 1
    else pending += 1
  })

  const total = tasks.length
  return {
    pending,
    partial,
    completed,
    total,
    percent: total === 0 ? 0 : Math.round((completed / total) * 100),
  }
}

export interface ImplementorStats extends StripStats {
  /** Null is the Unassigned bucket — a real answer a manager needs. */
  userId: number | null
  name: string
}

/**
 * The same four figures, per implementor — the Admin accordion.
 *
 * <h2>Grouped by owner, not by "mine"</h2>
 *
 * <p>{@link ProjectTask} has an owner and a backup owner, and `isMine` counts
 * either. That is the right rule for <em>filtering</em> — a backup who can act
 * on a task should see it — and the wrong one for a breakdown, where it would
 * file one task under two people and leave the rows summing to more than the
 * strip above them. So this groups on the accountable owner alone, and the rows
 * add up.
 *
 * <p>An inherited owner (`ownerIsInherited` — the task pinned nobody and the
 * read filled in the project's implementor) still counts as that person's here.
 * It is their work until somebody reassigns it; the task row is where the
 * inheritance is labelled.
 */
export function statsByImplementor(
  tasks: readonly ProjectTask[],
  nameOf: (userId: number) => string | null,
): ImplementorStats[] {
  const byOwner = new Map<number | null, ProjectTask[]>()
  tasks.forEach((task) => {
    const key = task.ownerUserId ?? null
    const bucket = byOwner.get(key)
    if (bucket) bucket.push(task)
    else byOwner.set(key, [task])
  })

  const rows: ImplementorStats[] = []
  byOwner.forEach((owned, userId) => {
    rows.push({
      userId,
      name:
        userId == null
          ? 'Unassigned'
          : // A resolved id whose user is missing from the directory falls back
            // to "User 41" rather than to Unassigned, so that bucket means
            // exactly what it says.
            (nameOf(userId) ?? `User ${userId}`),
      ...stripStats(owned),
    })
  })

  /*
    By name, with Unassigned last. Deliberately not by workload: a list that
    reorders itself as people finish tasks is one a manager has to re-read from
    the top every time they open it.
  */
  return rows.sort((a, b) => {
    if (a.userId == null) return 1
    if (b.userId == null) return -1
    return a.name.localeCompare(b.name)
  })
}

/* ── the service's schedule, and whether it is being kept ─────────────────
   Folded from the service's *whole* task list rather than from the reader's
   share of it — `TreeService.allTasks`, the same reading `totalTatDays` takes.
   "When did SIS start, when should it finish, and is it late" has one answer,
   and an implementor seeing a greener one than their manager because their own
   two tasks happen to be fine would be the worst kind of wrong. */

export interface ServiceDates {
  /**
   * When the first task of the service actually started, as it arrived on the
   * wire. Null while nothing has: a locked gate, or a service nobody has opened.
   */
  startedAt: string | null
  /**
   * The **latest** task due date across the service — the date by which all of
   * it should be finished, and therefore the service's expected completion.
   *
   * <p>Latest rather than earliest deliberately: the earliest is when the
   * *first* task is due, which is what a delay is measured from task by task
   * (see {@link deliveryHealth}) and not when the service is meant to be done.
   *
   * <p>`dueAt` is the server's, computed and recomputed against the working
   * calendar by C-105, so weekends, org holidays and resource leave are already
   * in it. Nothing here re-derives a date; this picks one of them.
   */
  expectedEndAt: string | null
}

/** The instant a wire date names, or `null` where it names nothing usable. */
function instant(value: string | null | undefined): number | null {
  if (!value) return null
  const parsed = new Date(value).getTime()
  return Number.isNaN(parsed) ? null : parsed
}

/**
 * The service's two dates, from its tasks — see {@link ServiceDates}.
 *
 * <p>Compared as instants rather than as strings: both fields are ISO, but
 * lexicographic order only agrees with chronological order while every value
 * carries the same offset, and one `+05:30` timestamp in a list of `Z` ones
 * would quietly pick the wrong row.
 */
export function serviceDates(tasks: readonly ProjectTask[]): ServiceDates {
  let startedAt: string | null = null
  let startedTime = Infinity
  let expectedEndAt: string | null = null
  let endTime = -Infinity

  for (const task of tasks) {
    const started = instant(task.startedAt)
    if (started != null && started < startedTime) {
      startedTime = started
      startedAt = task.startedAt ?? null
    }
    const due = instant(task.dueAt)
    if (due != null && due > endTime) {
      endTime = due
      expectedEndAt = task.dueAt ?? null
    }
  }

  return { startedAt, expectedEndAt }
}

/**
 * How the service is delivering, in the four words OB-05 asks for.
 *
 * <p>An escalation rather than a set: each step is worse than the one before,
 * and the worst task decides the service — the same worst-wins fold the RAG
 * colour makes upward from step to journey to client (plan §5.9).
 */
export type DeliveryStatus = 'ON_TIME' | 'AT_RISK' | 'DELAYED' | 'STUCK'

/** The status in the product's words. Never colour alone — blueprint §12.1. */
export const DELIVERY_LABEL: Record<DeliveryStatus, string> = {
  ON_TIME: 'On time',
  AT_RISK: 'At risk',
  DELAYED: 'Delayed',
  STUCK: 'Stuck',
}

/**
 * Past this many days beyond a task's own due date, Delayed becomes Stuck.
 *
 * <p>A week is the product's number, not a derived one: "delayed" is a
 * schedule that slipped and "stuck" is work nobody is moving, and a week is
 * where one stops being a plausible reading of the other.
 */
export const STUCK_AFTER_DAYS = 7

/**
 * The share of a task's TAT that must be gone before the service reads At risk.
 *
 * <p>75% is the server's own AMBER threshold — `ObRag` documents it — so a
 * strip reading **At risk** and a step wearing an amber dot are answering with
 * the same rule rather than with two opinions about the same clock.
 */
export const AT_RISK_TAT_PERCENT = 75

export interface DeliveryHealth {
  status: DeliveryStatus
  /**
   * Whole days the worst overdue task is past **its own** due date, rounded up;
   * `0` when nothing is overdue.
   *
   * <p>Rounded up rather than down, matching `ObDelayedProjectsService`: a task
   * due Friday and still open a minute into Monday has slipped, and reporting
   * zero until a whole day has elapsed would call that on time.
   *
   * <p>Calendar days, not working days — the browser has no working calendar
   * and inventing one here would put a second, disagreeing answer next to the
   * server's. The figure is shown beside the due date it is measured from, so
   * it is a number a reader can check rather than one they have to trust.
   */
  lateByDays: number
  /** The task that decided the status — named in the chip's hover text. */
  worstTask: string | null
}

/** One calendar day, in milliseconds. */
const DAY_MS = 86_400_000

/**
 * The service's delivery status, folded over its tasks.
 *
 * <h2>Settled tasks are not judged</h2>
 *
 * <p>`DONE` and `SKIPPED` are out of the fold entirely. A status answers "is
 * this going to be late", and a task that finished — even one that finished
 * late — is not a thing that can still slip. Without that rule a service would
 * wear **Stuck** forever on the strength of one task closed a fortnight ago,
 * and the chip would stop tracking anything a reader could act on.
 *
 * <h2>Delay is per task, not per service</h2>
 *
 * <p>Measured from each task's own due date rather than from the service's
 * expected end, which is what makes **Stuck** worth having: one task eight days
 * past its date is stuck whether or not the last task of the service is due
 * next month.
 *
 * @param now injected so a test can pin it — every branch below is a
 *            comparison against the clock, which is otherwise untestable.
 */
export function deliveryHealth(
  tasks: readonly ProjectTask[],
  now: number = Date.now(),
): DeliveryHealth {
  let lateByDays = 0
  let worstTask: string | null = null
  let atRisk: string | null = null

  for (const task of tasks) {
    if (isSettled(task)) continue

    const due = instant(task.dueAt)
    if (due != null && due < now) {
      const late = Math.ceil((now - due) / DAY_MS)
      if (late > lateByDays) {
        lateByDays = late
        worstTask = task.name
      }
      continue
    }

    // Not late yet, but most of its budget is gone. Only ever the reason for
    // At risk — an overdue task above has already outvoted it.
    if (atRisk == null && (task.tatUsedPercent ?? 0) >= AT_RISK_TAT_PERCENT) {
      atRisk = task.name
    }
  }

  if (lateByDays > STUCK_AFTER_DAYS) return { status: 'STUCK', lateByDays, worstTask }
  if (lateByDays > 0) return { status: 'DELAYED', lateByDays, worstTask }
  if (atRisk != null) return { status: 'AT_RISK', lateByDays: 0, worstTask: atRisk }
  return { status: 'ON_TIME', lateByDays: 0, worstTask: null }
}

/* ── the ribbon's own vocabulary ──────────────────────────────────────────
   Derived from the tree node rather than from `ObProjectStage`, because the
   node is scoped to the reader and the roll-up is not: in an implementor's
   view, the roll-up's "2/4 tasks" would be somebody else's four. */

/**
 * No `empty`.
 *
 * <p>There was one, for a Step the template published and scheduled nothing
 * into. `buildProjectTree` no longer produces such a node — those Steps are
 * dropped before anybody sees them — so the state was a branch nothing could
 * reach, which is the same dead vocabulary that came out of `stageRibbon.ts`.
 */
export type SegmentState = 'complete' | 'blocked' | 'current' | 'waiting' | 'pending'

export function segmentState(node: TreeStage): SegmentState {
  if (node.settled === node.tasks.length) return 'complete'
  if (node.tasks.some((t) => t.status === 'BLOCKED')) return 'blocked'
  /*
    `isCurrent` is the roll-up's — "holds the lowest-sequence task that is
    actually running" — and it is consulted second, after this reader's own
    tasks have had their say. In an unfiltered view the two agree; in a filtered
    one a Step running for somebody else still reads as running, which is true
    and is what a reader comparing their Step against the project needs.
  */
  if (node.tasks.some((t) => t.status === 'IN_PROGRESS') || node.stage.isCurrent) return 'current'
  if (node.tasks.some((t) => t.status === 'WAITING_ON_CLIENT')) return 'waiting'
  return 'pending'
}

/** The state in the product's words. Never colour alone — blueprint §12.1. */
export const SEGMENT_LABEL: Record<SegmentState, string> = {
  complete: 'Complete',
  blocked: 'Blocked',
  current: 'Running',
  waiting: 'Waiting',
  pending: 'Not started',
}

/**
 * Who a Step belongs to — "Priya Nair", or "Priya Nair +2" where it is shared.
 *
 * <p>First owner in task order rather than the most frequent: the order is the
 * template's own sequence, so the name is the person the Step opens on.
 */
export function ownerSummary(
  node: TreeStage,
  nameOf: (userId: number) => string | null,
): string | null {
  const owners: number[] = []
  node.tasks.forEach((task) => {
    if (task.ownerUserId != null && !owners.includes(task.ownerUserId)) owners.push(task.ownerUserId)
  })
  if (owners.length === 0) return null
  const first = nameOf(owners[0]) ?? `User ${owners[0]}`
  return owners.length === 1 ? first : `${first} +${owners.length - 1}`
}

/**
 * Which segment the ribbon opens on.
 *
 * <p>The running Step, else the first with outstanding work, else the first
 * with any tasks at all. The rule the deleted `defaultStageKey` applied to the
 * unscoped roll-up, re-stated against the tree node so an implementor opens on
 * their own running Step rather than on the project's.
 *
 * <p>Null only for a service with no Step to select at all.
 */
export function defaultStepKey(service: TreeService): number | null {
  // Every visible Step carries work now, so this is the list itself. Kept as a
  // named binding because the three fallbacks below read better against it.
  const withTasks = service.stages

  const running = withTasks.find((s) => segmentState(s) === 'current')
  if (running) return running.stage.stageKey

  const outstanding = withTasks.find((s) => s.settled < s.tasks.length)
  if (outstanding) return outstanding.stage.stageKey

  return withTasks[0]?.stage.stageKey ?? service.stages[0]?.stage.stageKey ?? null
}

/** One dot on the module strip: a Step, its position, and its state. */
export interface StageDot {
  stageKey: number
  name: string
  sequence: number
  state: SegmentState
  /** Not on this reader's timeline — held by somebody else, in the filtered view. */
  hidden: boolean
}

/**
 * Every Step of the service, in ribbon order, whether or not this reader's
 * timeline shows it.
 *
 * <p>The strip's dots answer "how is the whole service going", which the
 * filtered tree cannot: an implementor's `node.stages` holds only their own
 * Steps. So the walk is over the server's roll-up — every Step with work in it
 * — and each dot takes the richer reading from the tree where the reader has
 * the Step, and the roll-up's own complete / current / pending where they do
 * not.
 */
export function stageDots(node: TreeService): StageDot[] {
  return [...(node.service.stages ?? [])]
    .filter((stage) => stage.taskCount > 0)
    .sort((a, b) => a.sequence - b.sequence)
    .map((stage) => {
      const mine = node.stages.find((s) => s.stage.stageKey === stage.stageKey)
      const state: SegmentState = mine
        ? segmentState(mine)
        : stage.isComplete
          ? 'complete'
          : stage.isCurrent
            ? 'current'
            : 'pending'
      return { stageKey: stage.stageKey, name: stage.name, sequence: stage.sequence, state, hidden: mine == null }
    })
}

/* ── the review's three figures ───────────────────────────────────────────
   What a Step header prints beside its name. Deliberately *not*
   {@link stripStats}'s three buckets: those answer "how much of this is
   finished", these answer "how did the review go", which is the question a
   Step with a manager gate in it is actually asked. */

/**
 * Verified, rejected, in progress — the Step header's counts.
 *
 * <h2>These do not sum to the total, and must not be made to</h2>
 *
 * <p>A task nobody has started is in none of them, and a waived one is in none
 * either — it was settled without ever being reviewed, and filing it under
 * **Verified** would report a manager's approval that never happened. That is
 * the one figure on this header somebody might act on, so it says only what a
 * reviewer actually did.
 *
 * <p>{@link StripStats} is the fold whose buckets are exclusive and exhaustive;
 * this one is three questions, and the header says which is which by hue and by
 * its accessible name rather than by inviting the reader to add them up.
 */
export interface ReviewCounts {
  /** Approved: the review passed and the task is done. */
  verified: number
  /** Sent back with a reason and not yet re-submitted — the urgent bucket. */
  rejected: number
  /** Somebody is on it: started, blocked, waiting, or out for review. */
  inProgress: number
}

/**
 * Which of the three a task is in, or `null` for none of them.
 *
 * <h2>Rejected wins, and the order says why</h2>
 *
 * <p>A returned task is `IN_PROGRESS` — the row states materialise the status,
 * and a rejection puts it back with its implementor — so on the status alone
 * every rejected task would read as merely in progress and the red count would
 * be permanently zero. The rejected rows are the fact worth printing, so they
 * are read first.
 *
 * <p>Verified is `DONE` and never `SKIPPED`: see {@link ReviewCounts}. In
 * progress is {@link taskProgress}'s **partial** reading rather than the status
 * alone, so a task somebody has been ticking through without moving off
 * `PENDING` counts here exactly as it counts as partial on the Module strip.
 */
export function reviewBucket(task: ProjectTask): keyof ReviewCounts | null {
  if (task.items.some((i) => i.rowState === 'REJECTED')) return 'rejected'
  if (task.status === 'DONE') return 'verified'
  if (isSettled(task)) return null
  return taskProgress(task) === 'PARTIAL' ? 'inProgress' : null
}

export function reviewCounts(tasks: readonly ProjectTask[]): ReviewCounts {
  const counts: ReviewCounts = { verified: 0, rejected: 0, inProgress: 0 }
  tasks.forEach((task) => {
    const bucket = reviewBucket(task)
    if (bucket) counts[bucket] += 1
  })
  return counts
}
