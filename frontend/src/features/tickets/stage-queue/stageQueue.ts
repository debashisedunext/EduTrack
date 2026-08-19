import type { RoleCode } from '@/api/generated/model/roleCode'
import type { WorkflowStage } from '@/api/generated/model/workflowStage'
import type { WorkflowTemplate } from '@/api/generated/model/workflowTemplate'

/**
 * C-062 · S-31's pure rules.
 *
 * Blueprint line 1108 in full: *"each team's own worklist: 'Waiting in QA',
 * 'Waiting in Deployment', sorted by time-in-stage descending so the oldest
 * queued item is always on top. This is the landing page for QA and Deployment
 * resources, the way My Tasks is for Developers."*
 *
 * The sort is the server's — `GET /stages/queue` returns rows already ordered,
 * because a client sorting one page of a cursor-paginated list would order the
 * page rather than the queue and put the wrong ticket on top with complete
 * confidence.
 */

/** A stage the queue can be asked about, resolved out of the templates master. */
export interface QueueStage {
  stageCode: string
  displayName: string
  ownerRole?: RoleCode
  sequence: number
}

/**
 * Which stages this screen may show, deduplicated across every template.
 *
 * Same derivation S-17's Stage filter uses and for the same reason: there is no
 * dedicated stages endpoint, and `workflow_stages.template_id` is `NOT NULL`,
 * so `QA` on Standard Dev Flow and `QA` on Support Fast-Track are two rows. A
 * queue is asked about a stage *code*, so they collapse to one entry here.
 *
 * **`CLOSED` is excluded and deprecated stages are excluded.** A queue is work
 * waiting to be picked up; a closed ticket is not waiting for anybody, and a
 * retired stage accepts no new entry, so its queue can only ever shrink. Both
 * exclusions match `TicketListPage`'s stage filter exactly.
 */
export function queueStages(templates: readonly WorkflowTemplate[] | undefined): QueueStage[] {
  const byCode = new Map<string, QueueStage>()
  for (const template of templates ?? []) {
    for (const stage of template.stages ?? []) {
      if (!stage.stageCode || stage.stageCode === 'CLOSED' || stage.isDeprecated) continue
      if (byCode.has(stage.stageCode)) continue
      byCode.set(stage.stageCode, {
        stageCode: stage.stageCode,
        displayName: stage.displayName ?? stage.stageCode,
        ownerRole: stage.ownerRole,
        sequence: stage.sequence ?? 0,
      })
    }
  }
  return [...byCode.values()].sort((a, b) => a.sequence - b.sequence)
}

/**
 * Which queue this viewer lands on.
 *
 * **Matched on the stage's `ownerRole`, never on the stage code.** "Waiting in
 * QA" is the blueprint's example rather than its rule, and a screen keyed on the
 * literal `'QA'` would land a Deployment resource nowhere the day a project's
 * template calls that stage `RELEASE` — which is exactly what §7.4's designer
 * lets somebody do. The owner role is the fact the template actually states.
 *
 * Falls back to the first stage rather than to nothing: a PM or Admin opening
 * this screen owns no queue of their own, and an empty picker would read as a
 * broken page rather than as "pick a team".
 *
 * @returns the stage code to select, or `undefined` when no stage is loaded yet
 */
export function defaultStageFor(
  role: RoleCode | undefined,
  stages: readonly QueueStage[],
): string | undefined {
  if (stages.length === 0) return undefined
  const owned = role == null ? undefined : stages.find((s) => s.ownerRole === role)
  return (owned ?? stages[0]).stageCode
}

/**
 * The heading S-31 shows — "Waiting in QA", the blueprint's own words.
 *
 * Built from the stage's display name rather than its code so a renamed stage
 * reads as its team calls it.
 */
export function queueTitle(stage: QueueStage | undefined): string {
  return stage ? `Waiting in ${stage.displayName}` : 'Stage queue'
}

/**
 * Whether a row should be drawn as breached.
 *
 * Read from the server's `stageSlaBreached` rather than recomputed from
 * `timeInStageMins` against a stage SLA the client also holds. The server
 * measures in **working** minutes against the org calendar, resource leave and
 * holidays (CLAUDE.md), and a client recomputing it from wall-clock would mark a
 * Friday-evening handoff breached by Saturday morning — the exact case that rule
 * names. When the field is absent the row is simply not marked, which is the
 * safe direction: an unmarked breach is a missing cue, a phantom one is a lie.
 */
export function isBreached(row: { stageSlaBreached?: boolean }): boolean {
  return row.stageSlaBreached === true
}

/** Stage-code list for a stage the templates master has not loaded yet. */
export function findStage(
  stages: readonly QueueStage[],
  stageCode: string | null,
): QueueStage | undefined {
  return stageCode == null ? undefined : stages.find((s) => s.stageCode === stageCode)
}

/** Narrowed to what `queueStages` needs, so a test can pass a literal. */
export type QueueStageSource = Pick<
  WorkflowStage,
  'stageCode' | 'displayName' | 'ownerRole' | 'sequence' | 'isDeprecated'
>
