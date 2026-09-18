import type { ObProjectModuleService } from '@/api/generated/model/obProjectModuleService'
import type { ObProjectStage } from '@/api/generated/model/obProjectStage'

import { orderStages } from './stageRibbon'
import { isMine, type ProjectTask } from './useProjectTasks'
import { isMineOnly, type ObViewerScope } from './viewerScope'

/**
 * The project page's four levels, assembled — Module Service → Implementation
 * Stage → Task → Checklist.
 *
 * <h2>Why this is a fold and not a fetch</h2>
 *
 * <p>Every figure is already on the wire. `ObProjectDetail.moduleServices`
 * carries each service <em>and its own stage roll-up</em>, and the journey read
 * carries every task with the `stageKey` it belongs to and the checklist inside
 * it. So the tree is a regrouping of two responses this page already makes, not
 * a third request per branch.
 *
 * <h2>The stage roll-up is the service's, not the project's</h2>
 *
 * <p>`ObProjectDetail.stages` is the same data folded across every journey —
 * which is what the ribbon above the tree draws, and exactly what makes it
 * useless here: folded, "Configuration 1/3" cannot say whether SIS has finished
 * its one Configuration task or Attendance has finished none of its two. The
 * per-service list can, and the server builds both from one query so they
 * cannot disagree.
 *
 * <h2>Nothing is dropped, ever</h2>
 *
 * <p>A task whose `stageKey` matches no published stage of its service would be
 * invisible if this only walked the roll-up. That should not happen — both
 * sides fold `implementation_stage_id` the same way, and the contract says so
 * in three places — but "should not happen" is how tasks go missing quietly.
 * Unmatched tasks are collected under their own `stageName` and appended, so a
 * key mismatch surfaces as a stage in an odd position rather than as work
 * nobody can find.
 *
 * <h2>The fold is scoped to the reader</h2>
 *
 * <p>An implementor gets their own tasks and only the Steps holding them —
 * {@link ObViewerScope}. Every count below is then <em>their</em> count, which
 * is the whole point: a strip reading "3 of 6" to one person and "5 of 14" to
 * another is two honest answers to two different questions, and the Module
 * strip labels which one it is asking.
 *
 * <p><b>Filtering here is presentation, never permission.</b> CLAUDE.md's
 * row-scoping rule is categorical and the server has already applied it; this
 * only ever removes rows from a response the caller was entitled to. Steps
 * removed are counted rather than dropped silently — see `hiddenStageCount`,
 * which is what lets the timeline say how many it left out.
 */

/** One stage of one service, with that service's tasks in it. */
export interface TreeStage {
  stage: ObProjectStage
  tasks: ProjectTask[]
  /** `DONE` or `SKIPPED` — settled, the same reading the roll-up uses. */
  settled: number
  /** This stage holds at least one task the signed-in user is on. */
  hasMine: boolean
}

/** One module service, with its stages. */
export interface TreeService {
  service: ObProjectModuleService
  /**
   * The Steps this reader sees: scheduled, and — in an implementor's view —
   * holding work of theirs. A Step the template published and scheduled
   * nothing into is in nobody's list.
   */
  stages: TreeStage[]
  /** Tasks in `stages` — this reader's, when the scope is filtered. */
  tasks: ProjectTask[]
  /**
   * Every task of this service, whoever owns it and whichever Step it sits in.
   *
   * <p>The unscoped counterpart to {@link TreeService.tasks}, and the list the
   * strip's <em>unscoped</em> figures are folded from — the same reading
   * {@link TreeService.totalTatDays} takes and for the same reason: a schedule
   * is the service's, not a share of it. The start date, the expected end date
   * and the delivery status are all answers to "how is SIS going", which has
   * one answer whoever is looking at it.
   *
   * <p>Orphaned tasks are in here too — anything the roll-up did not account
   * for is still work this service has to finish, and a status folded from a
   * list that quietly dropped some of it would read better than the truth.
   */
  allTasks: ProjectTask[]
  taskCount: number
  settled: number
  /** Stages with tasks, all of them settled. An empty stage is not complete. */
  stagesComplete: number
  stageCount: number
  /**
   * Scheduled Steps this reader is not being shown — work that exists and is
   * somebody else's. Zero in an unfiltered view.
   *
   * <p>Counted rather than forgotten so the ribbon can say "5 Steps hold no
   * tasks of yours". A filter that cannot account for what it removed is
   * indistinguishable from a bug that lost it.
   *
   * <p>Stated rather than offered back: the page has no control that widens
   * the scope any more — the switch above the Steps chooses between the
   * reader's outstanding tasks and all of the reader's tasks, never between
   * their work and everybody's. See `taskFilter.ts`.
   *
   * <p>Steps nobody scheduled are <em>not</em> in here. They are hidden from
   * everyone, so they are not being withheld from anybody and counting them
   * would report empty segments as work somebody is missing.
   */
  hiddenStageCount: number
  hasMine: boolean
  /**
   * Σ of the pinned per-task TATs across **every** task of this service —
   * the reader's and everybody else's — in working days.
   *
   * <p>Deliberately not scoped, unlike every count above it. A count answers
   * "how much of this is mine"; a TAT is the service's schedule, and a schedule
   * does not shrink because fewer of its tasks are yours. It is also the only
   * reading under which the strips add up to the header's <em>Total TAT</em>,
   * which is `ObProject.totalTatDays` — the same Σ over the same pinned
   * figures, one level up. A reader who sums the strips and gets a different
   * number from the header has found a bug, and this is what keeps that from
   * being the design.
   *
   * <p>Pinned, not the master's current value: `tatDays` on the journey read
   * is what the task was instantiated with, so republishing a Module Service
   * does not move a running project's figure. Plan §5.10's own convention,
   * including summing across tasks that run in parallel.
   */
  totalTatDays: number
}

/**
 * The identity of a stage node.
 *
 * <p>`stageKey` alone will not do: it is folded onto the implementation stage,
 * so both services' Configuration carry the same key and one of them would open
 * the other. The journey is what separates them.
 */
export function stageNodeKey(journeyId: number, stageKey: number): string {
  return `${journeyId}:${stageKey}`
}

function settledCount(tasks: readonly ProjectTask[]): number {
  return tasks.filter((t) => t.status === 'DONE' || t.status === 'SKIPPED').length
}

/** A stage row for tasks whose key matched nothing the service published. */
function orphanStage(stageKey: number, name: string, tasks: readonly ProjectTask[]): ObProjectStage {
  return {
    stageKey,
    name,
    // Past the master's own range — `orderStages` already parks the Ungrouped
    // bucket at 9999, and these belong after even that.
    sequence: 10_000,
    taskCount: tasks.length,
    tasksOutstanding: tasks.length - settledCount(tasks),
    isComplete: false,
    isCurrent: false,
  }
}

export function buildProjectTree(
  services: readonly ObProjectModuleService[],
  tasks: readonly ProjectTask[],
  scope: ObViewerScope,
): TreeService[] {
  const meId = scope.meId
  const mineOnly = isMineOnly(scope)

  /*
    Every task of the service, whoever owns it. The scope is applied per Step
    further down rather than here, because "this Step has work, none of it
    yours" and "this Step has no work at all" are different answers and a
    pre-filtered list cannot tell them apart — the first is counted and said
    on the timeline, the second is never shown to anybody.
  */
  const byJourney = new Map<number, ProjectTask[]>()
  tasks.forEach((task) => {
    const bucket = byJourney.get(task.journeyId)
    if (bucket) bucket.push(task)
    else byJourney.set(task.journeyId, [task])
  })

  return services.map((service) => {
    const own = byJourney.get(service.journeyId) ?? []
    const claimed = new Set<number>()

    const stages: TreeStage[] = orderStages(service.stages ?? []).map((stage) => {
      const stageTasks = own.filter((t) => t.stageKey === stage.stageKey)
      stageTasks.forEach((t) => claimed.add(t.id))
      return {
        stage,
        tasks: stageTasks,
        settled: settledCount(stageTasks),
        hasMine: stageTasks.some((t) => isMine(t, meId)),
      }
    })

    // Anything the roll-up did not account for, grouped by the key the task
    // itself reports rather than by guesswork.
    const orphans = own.filter((t) => !claimed.has(t.id))
    const orphansByKey = new Map<number, ProjectTask[]>()
    orphans.forEach((task) => {
      const key = task.stageKey ?? 0
      const bucket = orphansByKey.get(key)
      if (bucket) bucket.push(task)
      else orphansByKey.set(key, [task])
    })
    orphansByKey.forEach((stageTasks, key) => {
      stages.push({
        stage: orphanStage(key, stageTasks[0].stageName ?? 'Ungrouped', stageTasks),
        tasks: stageTasks,
        settled: settledCount(stageTasks),
        hasMine: stageTasks.some((t) => isMine(t, meId)),
      })
    })

    /*
      A Step the template published and scheduled nothing into is shown to
      nobody.

      It used to be drawn, dashed, on the argument that a misconfigured Module
      Service is something somebody should see. In practice the default master
      publishes seven Steps and a service schedules work into two, so every
      ribbon carried five dead segments — the misconfiguration reading was
      wrong, and what the ribbon actually said was "this template has more
      Steps than this service uses", which is a fact about the master and not
      about this project. It belongs on the Implementation steps master, where
      somebody can act on it.

      This also fixes the counts above it: a service with two scheduled Steps
      now reads "1/2 steps" rather than "1/7", and a finished one reads 2/2
      instead of wearing a Complete chip beside 2/7.
    */
    const scheduled = stages.filter((s) => s.tasks.length > 0)

    /*
      The scope, applied last. `scheduled` is what the project has; `visible` is
      what this reader gets, and the difference is `hiddenStageCount`, which the
      timeline states in a line under the switch. A Step nobody scheduled is in
      neither, so it is never counted as something being withheld from anybody.
    */
    const visible = mineOnly
      ? scheduled
          .map((s) => {
            const mine = s.tasks.filter((t) => isMine(t, meId))
            return { ...s, tasks: mine, settled: settledCount(mine), hasMine: mine.length > 0 }
          })
          .filter((s) => s.tasks.length > 0)
      : scheduled

    const visibleTasks = visible.flatMap((s) => s.tasks)

    return {
      service,
      stages: visible,
      tasks: visibleTasks,
      // `own`, not `visibleTasks`: every task of the service — see the field.
      allTasks: own,
      taskCount: visibleTasks.length,
      settled: settledCount(visibleTasks),
      stagesComplete: visible.filter((s) => s.settled === s.tasks.length).length,
      stageCount: visible.length,
      hiddenStageCount: scheduled.length - visible.length,
      hasMine: own.some((t) => isMine(t, meId)),
      // `own`, not `visibleTasks`: the service's whole budget — see the field.
      totalTatDays: own.reduce((sum, t) => sum + t.tatDays, 0),
    }
  })
}

/**
 * Which services are open when the page loads.
 *
 * <p>None of them, which is the whole point of the tree: a project boarded
 * through several services opens as a short list of services rather than as
 * every task it has.
 *
 * <p>The exception is a project with exactly one service, where the closed row
 * is pure overhead — there is nothing to choose between, and a reader would
 * click it every single visit to reach the only branch there is.
 */
export function defaultOpenServices(tree: readonly TreeService[]): Set<number> {
  if (tree.length === 1) return new Set([tree[0].service.journeyId])
  return new Set()
}

/** The header's figures, summed across every service. */
export interface ProjectTally {
  stepsComplete: number
  stepsTotal: number
  tasksDone: number
  tasksTotal: number
  /**
   * Module Services the server reports finished, over every one this project
   * was boarded through.
   *
   * <p><b>Unscoped, unlike every count above it.</b> `isComplete` is the
   * service's own flag — the same one the Module strip's Complete chip reads —
   * so a module does not stop being finished because the reader owns none of
   * its tasks, and the header's denominator is the project's module count
   * whatever the page is filtered to. A filtered reader who saw "1/1 modules"
   * because their own work happened to sit in one service would be reading a
   * fact about themselves as a fact about the engagement.
   */
  modulesComplete: number
  modulesTotal: number
  /**
   * `tasksDone` over `tasksTotal`, rounded, 0–100 — how far along the whole
   * project is, in one figure.
   *
   * <p>Deliberately the same arithmetic as {@link StripStats.percent}, over
   * the same settled reading, so the header is the strips added up rather than
   * a second opinion about them. Partial work is not credited half a task
   * here either: a project reads as 60% done when 60% of its tasks are
   * finished, not when somebody has opened the rest.
   *
   * <p>Zero for a project with no task at all, which is a misconfigured Module
   * Service rather than a project nobody has started — the same reading
   * `ObProject.stagesTotal` documents.
   */
  completionPercent: number
}

/**
 * The header, read off the same fold as the strips below it.
 *
 * <p>It used to read `ObProjectDetail.stages` — the project-level roll-up —
 * which is the right source for an unfiltered page and the wrong one the moment
 * the strips are scoped: an implementor would get "Steps 2/7, Tasks done 3/9"
 * above three strips that between them account for six tasks. One fold, one
 * answer, and the header cannot drift from the tree under it.
 */
export function projectTally(tree: readonly TreeService[]): ProjectTally {
  const summed = tree.reduce(
    (acc, service) => ({
      stepsComplete: acc.stepsComplete + service.stagesComplete,
      // Every Step on the reader's ribbon, empty ones included. The strip beside
      // it counts `stageCount` the same way, so a header reading "1/7 steps"
      // and a strip reading "1/7 steps" are the same seven segments.
      stepsTotal: acc.stepsTotal + service.stageCount,
      tasksDone: acc.tasksDone + service.settled,
      tasksTotal: acc.tasksTotal + service.taskCount,
      // The service's own flag, not this reader's share of it — see the field.
      modulesComplete: acc.modulesComplete + (service.service.isComplete ? 1 : 0),
      modulesTotal: acc.modulesTotal + 1,
    }),
    {
      stepsComplete: 0,
      stepsTotal: 0,
      tasksDone: 0,
      tasksTotal: 0,
      modulesComplete: 0,
      modulesTotal: 0,
    },
  )

  return {
    ...summed,
    completionPercent:
      summed.tasksTotal === 0 ? 0 : Math.round((summed.tasksDone / summed.tasksTotal) * 100),
  }
}

/**
 * The DOM id of a task row.
 *
 * <p>Lives here rather than in the tree component because both the tree (which
 * scrolls to it) and the stage body (which renders it) need the same string,
 * and having the renderer import it from the scroller would point the
 * dependency the wrong way round.
 */
export function taskAnchorId(taskId: number): string {
  return `ob-task-${taskId}`
}

/** Where a task sits, for opening the path down to it. */
export interface TaskPath {
  journeyId: number
  stageKey: number
}

export function findTaskPath(tree: readonly TreeService[], taskId: number): TaskPath | null {
  for (const service of tree) {
    for (const stage of service.stages) {
      if (stage.tasks.some((t) => t.id === taskId)) {
        return { journeyId: service.service.journeyId, stageKey: stage.stage.stageKey }
      }
    }
  }
  return null
}
