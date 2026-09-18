import type { ObMyTask } from '@/api/generated/model'

import type { Slice } from './obProjectBoard'

/**
 * The implementor's own open tasks, cut into the four states their donut
 * draws — Delayed, At risk, In progress, Pending.
 *
 * <h2>Tasks, never checklist items</h2>
 *
 * <p>`GET /onboarding/my-tasks` answers in tasks: one row per step the caller
 * owns. Its `rowsOut` / `rowsReturned` / `rowsApproved` fields count the
 * checklist inside a task and are deliberately untouched here — the figure
 * this donut shows is a count of tasks, and folding a task with nine ticks in
 * would make one person's four tasks read as thirty-six.
 *
 * <h2>Every bucket is a field the row already carries</h2>
 *
 * <p>No date arithmetic happens here. `isOverdue` is the server's own clock,
 * computed against the working calendar — CLAUDE.md puts that maths in exactly
 * one place, and comparing `dueAt` in the browser would be a second answer to
 * a question the response already answered, got from a calendar that knows
 * nothing about weekends, org holidays or leave.
 *
 * <h2>The four are disjoint, and overdue wins</h2>
 *
 * <p>A task can be In progress and late at once. Where it is, it counts as
 * <b>Delayed</b> and nowhere else: the four sum to the queue, so the donut's
 * centre is the number of tasks the person actually holds rather than a total
 * that double-counts the ones in trouble. That is also why lateness is tested
 * first below.
 *
 * <p><b>At risk means blocked or waiting on the client</b> — work that is not
 * late yet and cannot move. It is the reading the rest of the module already
 * takes: the stuck table is built from these two statuses, and the workload
 * grid keeps a `blockedWaiting` column beside its `atRisk` one. A task
 * approaching its due date would be the other reading, and it is not
 * available — a row carries no "due soon" flag, and deciding it here would be
 * the working-calendar arithmetic the section above keeps out of the browser.
 * If that is the rule wanted, it belongs on the server beside `isOverdue`.
 */
export const MY_TASK_BUCKETS = ['delayed', 'at-risk', 'in-progress', 'pending'] as const

export type MyTaskBucket = (typeof MY_TASK_BUCKETS)[number]

const LOOK: Record<MyTaskBucket, { label: string; colour: string }> = {
  /* The same ramp the project board speaks, so "late" is one colour across
     the screen rather than whatever the fourth chart series happens to be. */
  delayed: { label: 'Delayed', colour: 'var(--danger)' },
  'at-risk': { label: 'At risk', colour: 'var(--status-delayed)' },
  'in-progress': { label: 'In progress', colour: 'var(--info)' },
  pending: { label: 'Pending', colour: 'var(--text-secondary)' },
}

/** Which of the four a single task is in. Overdue first — see the header. */
export function myTaskBucket(task: Pick<ObMyTask, 'status' | 'isOverdue'>): MyTaskBucket {
  if (task.isOverdue) return 'delayed'
  if (task.status === 'BLOCKED' || task.status === 'WAITING_ON_CLIENT') return 'at-risk'
  if (task.status === 'IN_PROGRESS') return 'in-progress'
  return 'pending'
}

/**
 * The donut's slices, in the order the legend reads them: worst first, so a
 * reader scanning for trouble scans from the top.
 *
 * <p>Empty buckets are dropped rather than drawn at zero width — the schedule
 * donut's own rule, and for its reason: a zero-width arc is a slice nobody can
 * hover, click or see.
 */
export function myTaskSlices(tasks: readonly ObMyTask[]): Slice<ObMyTask>[] {
  return MY_TASK_BUCKETS.map((bucket) => ({
    key: bucket,
    label: LOOK[bucket].label,
    colour: LOOK[bucket].colour,
    rows: tasks.filter((task) => myTaskBucket(task) === bucket),
  })).filter((slice) => slice.rows.length > 0)
}
