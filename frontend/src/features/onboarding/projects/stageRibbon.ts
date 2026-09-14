import type { ObProjectStage } from '@/api/generated/model/obProjectStage'

/**
 * The implementation-stage ribbon's own vocabulary, kept pure and tested apart
 * from the component that draws it.
 *
 * ## Why the ribbon is stages and not tasks
 *
 * The onboarding model is four levels — Module Service → Stage → Task → Task
 * list ({@link ObJourneyTemplateStage}'s own words) — and the ribbon had been
 * drawing the *third*. A template with twenty tasks became a twenty-stop strip
 * with no grouping, so the question people open a project with ("which part of
 * the rollout are we in?") had no answer on the screen that should hold it.
 *
 * Stages answer it in seven stops, and they are the vocabulary operations
 * already use: Configuration, Data Migration, Reports, Training, Communication,
 * Third Party Integration, Web/App Reflection.
 *
 * ## This reads the roll-up the server already computes
 *
 * Nothing here re-derives progress. `ObProjectReadRepository.STAGE_ROLLUP`
 * joins `ob_journey_steps → ob_journey_template_steps →
 * ob_journey_template_stages`, folds onto `implementation_stage_id` so a
 * project boarded through two module services reports six stages rather than
 * twelve, and counts `DONE` and `SKIPPED` together as settled. These functions
 * only present what it returns — a second opinion about the same fact is how
 * two screens end up disagreeing.
 */

/**
 * Four states, and `empty` is the one worth having.
 *
 * A stage with no tasks is not pending — nothing is coming — and drawing it as
 * pending would have a reader waiting on work that was never scheduled. It is a
 * misconfigured Module Service, and the ribbon says so rather than flattering
 * it. `ObProjectStage.taskCount` of zero is the only signal for it, which is
 * why this is tested on its own.
 */
export type StageState = 'complete' | 'current' | 'pending' | 'empty'

export function stageState(stage: ObProjectStage): StageState {
  if (stage.taskCount === 0) return 'empty'
  if (stage.isComplete) return 'complete'
  if (stage.isCurrent) return 'current'
  return 'pending'
}

/**
 * Settled tasks — `DONE` and `SKIPPED` both, because `tasksOutstanding` is what
 * the server counts and a waived task is not outstanding work.
 */
export function stageSettled(stage: ObProjectStage): number {
  return Math.max(0, stage.taskCount - stage.tasksOutstanding)
}

/**
 * The fill of the rail under each stop, 0–1.
 *
 * An empty stage fills nothing. Dividing by zero to reach 100% would paint a
 * stage nobody has scheduled as a finished one, which is the single most
 * misleading answer available here — the same call {@link stageProgress} makes
 * for the project as a whole.
 */
export function stageFraction(stage: ObProjectStage): number {
  if (stage.taskCount <= 0) return 0
  return Math.min(1, stageSettled(stage) / stage.taskCount)
}

/** `3/5 tasks`, or `No tasks` where the stage holds none. */
export function stageCountLabel(stage: ObProjectStage): string {
  if (stage.taskCount === 0) return 'No tasks'
  return `${stageSettled(stage)}/${stage.taskCount} tasks`
}

/**
 * Ribbon order: by the master's sequence, then by key.
 *
 * The server already returns them this way, so this is a guard rather than a
 * transformation — a ribbon whose stops reorder because a response arrived in a
 * different order would be a genuinely confusing bug, and sorting a
 * seven-element array to rule it out costs nothing.
 *
 * `stageKey` breaks ties because the Ungrouped bucket carries sequence 9999 and
 * several of them can exist — one per stage group with no implementation stage.
 */
export function orderStages(stages: readonly ObProjectStage[]): ObProjectStage[] {
  return [...stages].sort((a, b) => a.sequence - b.sequence || a.stageKey - b.stageKey)
}

/**
 * Which stop the page opens on.
 *
 * The running stage where the server names one — `isCurrent` is "holds the
 * lowest-sequence task that is actually running", which is the stage somebody
 * opening this project is being asked about. Failing that the first stage with
 * outstanding work, which is where attention goes next; failing *that* the
 * first stage with any tasks at all, so a finished project opens on something
 * rather than on nothing.
 *
 * Returns null only for a project with no stages, where there is no stop to
 * select and the ribbon does not render.
 */
export function defaultStageKey(stages: readonly ObProjectStage[]): number | null {
  const ordered = orderStages(stages)
  const current = ordered.find((s) => s.isCurrent && s.taskCount > 0)
  if (current) return current.stageKey

  const outstanding = ordered.find((s) => s.taskCount > 0 && s.tasksOutstanding > 0)
  if (outstanding) return outstanding.stageKey

  const any = ordered.find((s) => s.taskCount > 0)
  return any ? any.stageKey : (ordered[0]?.stageKey ?? null)
}
