import { isSettled } from './moduleStripStats'
import type { TreeStage } from './projectTree'
import type { ProjectTask } from './useProjectTasks'

/**
 * Which of the reader's tasks the project page draws — the **Pending** /
 * **Show all** switch above an open module's timeline.
 *
 * <h2>Two readings of one list, and the page opens on the working one</h2>
 *
 * <p>A reader arriving at a project is nearly always asking "what is still on
 * me here", and a timeline that opens with every finished task in it answers a
 * different question first. So `PENDING` is the arrival state: outstanding work
 * only, which is everything not `DONE` and not `SKIPPED`.
 *
 * <p>`ALL` is the same list with the finished work back in it — the reading
 * somebody wants when they are checking what was done rather than what is
 * left. It is one press away and it says what it is showing, because a filter
 * whose effect a reader cannot see is indistinguishable from missing data.
 *
 * <h2>Outstanding, not `PENDING` the status</h2>
 *
 * <p>The switch's `PENDING` and `ObJourneyStepStatus.PENDING` are deliberately
 * not the same set: a task that is `IN_PROGRESS`, `BLOCKED` or
 * `WAITING_ON_CLIENT` is precisely the work a reader came to find, and filing
 * it under "not pending" because somebody has already touched it would hide
 * the most urgent rows on the page. The complement of *settled* is the only
 * reading under which Pending and Show all differ by exactly the finished
 * tasks, which is what the two labels promise.
 *
 * <h2>This filters what is drawn, never what is counted</h2>
 *
 * <p>Every figure on the page — the header's completion, the Module strip's
 * three buckets, a Step's verified / rejected / in progress — is folded from the unfiltered
 * tree, so pressing **Pending** cannot move a percentage. A denominator that
 * changed with a view control would make two readers of the same project
 * disagree about how far along it is.
 *
 * <p>It is also presentation and never permission, exactly like
 * {@link ObViewerScope}: the rows are already the reader's own by the time they
 * reach here. See `viewerScope.ts`.
 */
export type ObTaskFilter = 'PENDING' | 'ALL'

/** The switch's two labels, in one place so the control and its tests agree. */
export const TASK_FILTER_LABEL: Record<ObTaskFilter, string> = {
  PENDING: 'Pending',
  ALL: 'Show all',
}

/** One Step as the filter leaves it — the Step itself, and the rows to draw. */
export interface StepView {
  /** The Step, unfiltered: every figure on its header is folded from this. */
  step: TreeStage
  /** The rows this filter draws, in the Step's own order. */
  tasks: ProjectTask[]
  /** Finished tasks this filter left out of `tasks`. Zero under `ALL`. */
  hiddenCount: number
}

/**
 * The Steps to draw, and what to draw under each.
 *
 * <p>A Step with nothing left to do drops out under `PENDING` rather than
 * rendering as an empty accordion — a row that opens onto nothing is a worse
 * answer than not being there, and the count of them is on the switch's own
 * line so the reader knows what was removed.
 */
export function stepViews(stages: readonly TreeStage[], filter: ObTaskFilter): StepView[] {
  if (filter === 'ALL') {
    return stages.map((step) => ({ step, tasks: step.tasks, hiddenCount: 0 }))
  }
  return stages.flatMap((step) => {
    const tasks = step.tasks.filter((task) => !isSettled(task))
    if (tasks.length === 0) return []
    return [{ step, tasks, hiddenCount: step.tasks.length - tasks.length }]
  })
}

/** Steps this service has that the filter is not drawing — never negative. */
export function hiddenStepCount(stages: readonly TreeStage[], filter: ObTaskFilter): number {
  return stages.length - stepViews(stages, filter).length
}

