import { isSettledStatus, isStartedStatus, taskProgress } from './moduleStripStats'
import { overdue } from './taskDates'
import type { ProjectTask } from './useProjectTasks'

/**
 * How a task's status is named and, on the task strip, coloured.
 *
 * <p>One module for the vocabulary so the pill in the task popup and the dot
 * on the strip cannot spell a status two ways, and pure so both readings are
 * testable without rendering anything.
 */

/** `ob_journey_steps.status`, in the words the screen uses for it. */
export const TASK_STATUS_LABEL: Record<string, string> = {
  PENDING: 'Not started',
  IN_PROGRESS: 'In progress',
  BLOCKED: 'Blocked',
  WAITING_ON_CLIENT: 'Waiting on client',
  // Named for what the reader is waiting on rather than for the transition —
  // "Pending review" would read as a status the task chose, and it did not:
  // its owner has finished and an OB Manager has not read it yet.
  PENDING_REVIEW: 'Awaiting review',
  DONE: 'Complete',
  SKIPPED: 'Waived',
}

/**
 * The four states a task row is coloured by — <b>not</b> the six statuses.
 *
 * <h2>Why four, and why one of them is not a status at all</h2>
 *
 * <p>A task row is read by the dozen, in a column a reader scans rather than
 * studies, and what they are scanning for is which rows need them today. Six
 * colours answer "what is the state machine doing"; these four answer "is this
 * done, is it moving, is it late, or has nobody started it" — which is the
 * question somebody opening their own project actually arrived with.
 *
 * <p><b>Overdue is derived, never stored.</b> `ob_journey_steps` has no overdue
 * status and must not grow one: lateness is a fact about the clock against
 * `dueAt`, so a row goes red on its own at the due minute rather than waiting
 * for a scanner to write a status, and goes back to its real colour the moment
 * somebody moves the date.
 *
 * <p>The exact status is never lost — it rides on the dot as its accessible
 * name and its tooltip (see {@link taskDotLabel}), and the task popup still
 * prints it as a word. The strip trades six words for four colours; the two
 * places a reader asks for detail still have all six.
 */
export type TaskDotState = 'PENDING' | 'IN_PROCESS' | 'OVERDUE' | 'COMPLETED'

export const TASK_DOT_LABEL: Record<TaskDotState, string> = {
  PENDING: 'Pending',
  IN_PROCESS: 'In process',
  OVERDUE: 'Overdue',
  COMPLETED: 'Completed',
}

/**
 * What the status is called when the four-state name does not already say it.
 *
 * <p>Blocked and Waiting on client both colour as In process — they are states
 * a task reaches by being worked on, which is the same reading
 * `moduleStripStats.STARTED` takes, and the strip's buckets and the dots would
 * otherwise disagree about the same row. Waived colours as Completed because
 * it is settled work. In all three the word is worth keeping, so it is
 * appended to the dot's name rather than dropped.
 */
const NUANCE: Record<string, string> = {
  BLOCKED: 'blocked',
  WAITING_ON_CLIENT: 'waiting on client',
  SKIPPED: 'waived',
}

/**
 * Settled first, then the clock, then whether anybody has touched it.
 *
 * <p>The order is the whole rule. Settled wins over overdue — work finished
 * late is finished, and a green row that turns red for ever the day after its
 * due date would be a screen arguing with itself. Overdue wins over in
 * process, because "this is late" is the more urgent of the two facts and the
 * row has only one colour to say it with.
 *
 * <p>Started is `taskProgress`'s reading rather than the status alone, so a
 * task somebody has been ticking through all morning without moving off
 * `PENDING` reads as in process here exactly as it counts as partial on the
 * Module strip above it.
 */
export function taskDotState(task: ProjectTask): TaskDotState {
  const state = statusDotState(task.status, overdue(task.dueAt))
  /*
    Only the untouched reading is worth a second look: a task still on PENDING
    whose check list somebody has been ticking through is in process, and
    `taskProgress` is the one place that reading lives.
  */
  return state === 'PENDING' && taskProgress(task) === 'PARTIAL' ? 'IN_PROCESS' : state
}

/**
 * The same four-state reading from the two facts a row carries on its own —
 * for a queue whose rows are not {@link ProjectTask}s.
 *
 * <p>My Tasks is the caller: its rows carry the status and the server's own
 * `isOverdue` but no check list, so the "somebody has ticked something" signal
 * above is not available there and a task reads as Pending until its status
 * moves. Every other rule — settled beats late, late beats moving — is this
 * function, shared, rather than a second copy that could drift from it.
 */
export function statusDotState(status: string, isOverdue: boolean): TaskDotState {
  if (isSettledStatus(status)) return 'COMPLETED'
  if (isOverdue) return 'OVERDUE'
  return isStartedStatus(status) ? 'IN_PROCESS' : 'PENDING'
}

/**
 * The dot's accessible name and tooltip — its colour in words.
 *
 * <p>Never colour alone (blueprint §12.1). The dot replaced a pill that said
 * the status in text, so the name it carries is the only place the status
 * survives for a reader who cannot see the hue, and it has to be both: the
 * four-state word the colour means, and the exact status where that says more.
 */
export function taskDotLabel(task: ProjectTask): string {
  return dotLabel(taskDotState(task), task.status)
}

/** The same name from a state already worked out — what My Tasks' rows use. */
export function dotLabel(state: TaskDotState, status: string): string {
  const base = TASK_DOT_LABEL[state]
  const nuance = NUANCE[status]
  return nuance ? `${base} — ${nuance}` : base
}
