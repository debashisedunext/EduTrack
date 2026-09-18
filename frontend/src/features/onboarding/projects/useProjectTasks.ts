import * as React from 'react'
import { useQueries } from '@tanstack/react-query'

import { getGetObJourneyQueryOptions } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import type { ObJourneyStepDoc } from '@/api/generated/model/obJourneyStepDoc'
import type { ObJourneyStepItem } from '@/api/generated/model/obJourneyStepItem'
import type { ObJourneyStepStatus } from '@/api/generated/model/obJourneyStepStatus'
import type { ObProjectDetail } from '@/api/generated/model/obProjectDetail'

/**
 * Every task of a project, flattened across its module services.
 *
 * <h2>Why flattened, when the page draws a tree</h2>
 *
 * <p>Flat is the shape the responses arrive in — one journey read per module
 * service — and regrouping them is `projectTree.ts`'s job rather than this
 * hook's. Keeping the fetching and the grouping apart is what lets the tree be
 * a pure function over data somebody else fetched, and therefore what lets it
 * be tested without a query client.
 *
 * <p>No grouping is implied by position: every task carries the journey it came
 * from and that service's pinned name, so {@link ProjectTask.journeyId} is what
 * the tree files on and {@link ProjectTask.serviceName} is what it prints.
 *
 * <h2>One request per module service, in parallel</h2>
 *
 * <p>`useQueries` rather than a loop of `useGetObJourney`, because the number of
 * services is data and hooks cannot be called in a loop over it. A project
 * carries a handful — one per service bought — and the journey read now returns
 * each task's sub-tasks with it, so this is the whole hierarchy in as many
 * requests as the project has services.
 *
 * <p>They are cached under the same key `useGetObJourney` uses, so the step
 * panel and anything else reading one journey share these responses rather than
 * refetching them.
 */
export interface ProjectTask {
  id: number
  journeyId: number
  /** The pinned module-service name — `ObProjectModuleService.serviceName`. */
  serviceName: string
  /** Position within its own journey, which is why it is not unique here. */
  sequence: number
  name: string
  status: ObJourneyStepStatus
  ownerUserId?: number | null
  /**
   * `ownerUserId` was resolved from the project's implementor rather than from
   * the module service — the task pinned nobody, and the read filled it in. The
   * strip labels it, because an inherited owner read as a deliberate one makes
   * somebody accountable for a task nobody assigned them.
   */
  ownerIsInherited: boolean
  backupOwnerUserId?: number | null
  tatDays: number
  requiresSignoff: boolean
  /**
   * When work on this task actually began — null while it is still pending.
   *
   * <p>The Module strip folds the earliest of these into the service's **Start
   * date**: a service starts when the first thing in it does, and there is no
   * separate column that says so. `ObJourneySummary.startedAt` is the journey's
   * own answer to the same question, and it is deliberately not used here —
   * reading it would mean plumbing the journey summary down beside the tasks
   * for one field that the tasks already carry.
   */
  startedAt?: string | null
  dueAt?: string | null
  /** Working hours consumed over budget, server-computed — see the contract. */
  tatUsedPercent?: number | null
  /**
   * Folded identically to `ObProjectStage.stageKey`, so filing a task under a
   * ribbon stop is an equality test. Null only where the read did not resolve
   * one, which `ObJourneyReadRepository.stagesOfJourney` does not produce.
   */
  stageKey: number | null
  stageName: string | null
  /** The sub-tasks, already on the journey read — see `itemsOfJourney`. */
  items: ObJourneyStepItem[]
  /** The template's required documents, satisfaction resolved server-side. */
  docs: ObJourneyStepDoc[]
}

export interface ProjectTasksResult {
  tasks: ProjectTask[]
  isPending: boolean
  isError: boolean
}

export function useProjectTasks(project: ObProjectDetail | undefined): ProjectTasksResult {
  const services = React.useMemo(() => project?.moduleServices ?? [], [project?.moduleServices])

  const results = useQueries({
    queries: services.map((service) => getGetObJourneyQueryOptions(service.journeyId)),
  })

  /*
    `results` is a fresh array on every render — useQueries rebuilds it — so the
    flattening is memoised on the response payloads rather than on the array
    itself. Without that every render would rebuild every task object and
    re-render the whole stage body on unrelated state changes.
  */
  const payloads = results.map((r) => r.data)
  const isPending = results.some((r) => r.isPending)
  const isError = results.some((r) => r.isError)

  const tasks = React.useMemo(() => {
    const out: ProjectTask[] = []
    services.forEach((service, index) => {
      const steps = payloads[index]?.data?.steps ?? []
      steps.forEach((step) => {
        out.push({
          id: step.id,
          journeyId: service.journeyId,
          serviceName: service.serviceName,
          sequence: step.sequence,
          name: step.name,
          status: step.status,
          ownerUserId: step.ownerUserId,
          ownerIsInherited: step.ownerIsInherited ?? false,
          backupOwnerUserId: step.backupOwnerUserId,
          tatDays: step.tatDays ?? 0,
          requiresSignoff: step.requiresSignoff ?? false,
          startedAt: step.startedAt,
          dueAt: step.dueAt,
          tatUsedPercent: step.tatUsedPercent ?? null,
          stageKey: step.stageKey ?? null,
          stageName: step.stageName ?? null,
          items: step.items ?? [],
          docs: step.docs ?? [],
        })
      })
    })
    /*
      Service first, then the journey's own sequence. Sorting by sequence alone
      would interleave two services' tasks into an order neither of them has —
      "task 1, task 1, task 2, task 2" reads as a single confused list rather
      than as two services' work sitting in one stage.
    */
    return out.sort(
      (a, b) => a.serviceName.localeCompare(b.serviceName) || a.sequence - b.sequence,
    )
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [services, ...payloads])

  return { tasks, isPending, isError }
}

/**
 * Whether a task is the signed-in user's.
 *
 * <p>Owner **or** backup owner, which is the same rule
 * `stepActions.mayActOnStep` applies to the action bar — a backup owner who can
 * act on a task but does not see it marked as theirs would be reading a
 * different answer from two parts of one screen.
 */
export function isMine(task: ProjectTask, meId: number | null | undefined): boolean {
  if (meId == null) return false
  return task.ownerUserId === meId || task.backupOwnerUserId === meId
}
