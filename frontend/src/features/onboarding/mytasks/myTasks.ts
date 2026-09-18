import type { Me } from '@/api/generated/model/me'
import type { ObJourneyStepView } from '@/api/generated/model/obJourneyStepView'
import type { ObMyTask } from '@/api/generated/model/obMyTask'

import {
  dotLabel,
  statusDotState,
  type TaskDotState,
} from '@/features/onboarding/projects/taskStatus'
import type { ProjectTask } from '@/features/onboarding/projects/useProjectTasks'

/**
 * My Tasks' own vocabulary, kept pure and tested apart from the two screens.
 */

/** The module role that owns onboarding tasks. */
export const OB_STEP_OWNER = 'OB_STEP_OWNER'

/**
 * Whether this person is an Implementor.
 *
 * <h2>“Implementor” is a label, not a role code</h2>
 *
 * <p>There is no `OB_IMPLEMENTOR`. The module's roles are `OB_ADMIN`,
 * `OB_MANAGER`, `OB_SALES`, `OB_STEP_OWNER` and `OB_VIEWER`, and the one that
 * means "this person owns onboarding tasks" is `OB_STEP_OWNER` — which is what
 * the Module Service designer already labels **Implementor** on its owner
 * column. So that is what this reads.
 *
 * <p>A project's `implementorUserId` is a different thing: who runs a project,
 * and the fallback owner of a task nobody was assigned. Related, and not a role.
 * The server counts those tasks as yours anyway, so somebody who is an
 * implementor on projects without holding the role still gets their work — they
 * just reach it from the project page rather than from this queue.
 *
 * <h2>This decides what is easy to find, never what is permitted</h2>
 *
 * <p>`/onboarding/my-tasks` returns the caller's own tasks and nothing else, so
 * the worst a wrong answer here can do is hide a screen from somebody entitled
 * to it — never show one task to the wrong person. That is the acceptable
 * direction of the error, and the same bargain the sidebar's `adminOnly` makes.
 */
export function isObImplementor(me: Me | undefined | null): boolean {
  return me?.moduleRoles?.ONBOARDING === OB_STEP_OWNER
}

/**
 * The status labels, in the product's words rather than the enum's.
 *
 * <p>Repeated from `ObProjectStageBody` rather than imported from it: that map
 * is private to a component in another feature, and exporting it to reach
 * across would couple two screens through a constant neither owns. Six strings
 * is cheaper than that coupling, and the test asserts they agree.
 */
export const MY_TASK_STATUS_LABEL: Record<string, string> = {
  PENDING: 'Not started',
  IN_PROGRESS: 'In progress',
  BLOCKED: 'Blocked',
  WAITING_ON_CLIENT: 'Waiting on client',
  // Reads two ways on this page and is right both times: on an implementor's
  // own row it means "with the manager, not with you", and on a manager's it
  // means "waiting on you". The row's assignee says which, and the popup says
  // it in a sentence.
  PENDING_REVIEW: 'Awaiting review',
  DONE: 'Complete',
  SKIPPED: 'Waived',
}

export function myTaskStatusLabel(status: string): string {
  return MY_TASK_STATUS_LABEL[status] ?? status
}

/** The row a dot is read off: its status, and whether the clock has passed. */
type DotRow = Pick<ObMyTask, 'status' | 'isOverdue'>

/**
 * The queue row's state as one of the task strip's four colours.
 *
 * <h2>Overdue is a colour here, and still its own column</h2>
 *
 * <p>Nothing moves off the Due date cell — a task can be In progress and
 * overdue at once, the date keeps saying which day it was due and the word
 * "overdue" beside it. What changes is that the status column now answers the
 * scanning question rather than the state-machine one: is this done, moving,
 * late, or has nobody started it.
 *
 * <p>The ordering and the four states are {@link statusDotState}'s, shared with
 * the task strip, so a task cannot be red on a project page and grey on this
 * one. The server's own `isOverdue` is the clock: this screen is paged and its
 * rows are not re-read on a timer, so comparing `dueAt` here would only give a
 * second answer to a question the response already answered.
 *
 * <h2>Pending covers a started check list</h2>
 *
 * <p>On the project page an untouched status with a half-ticked check list
 * reads as In process. The queue has no check lists in it — the rows would each
 * cost a journey read — so such a task sits at Pending until its status moves.
 * Colouring it from what this screen actually knows beats a per-row request for
 * one hue.
 */
export function myTaskDotState(row: DotRow): TaskDotState {
  return statusDotState(row.status, row.isOverdue)
}

/**
 * The dot's accessible name — its colour in words, plus the exact status where
 * that says more. Never colour alone (blueprint §12.1): four hues replaced six
 * words in that column, and this is where the other two survive.
 */
export function myTaskDotLabel(row: DotRow): string {
  return dotLabel(myTaskDotState(row), row.status)
}

/** What sits between the two halves of a merged label. */
const JOIN = ' — '

/** A separator a name already ends with, so the join is never doubled. */
const LEADING_SEPARATOR = /^[\s—–\-:·|]+/

/**
 * `The Shri Ram School — ERP`: the client and the project it runs, as one label.
 *
 * <h2>Why they share a column</h2>
 *
 * <p>Neither answers much alone. A project called "ERP" says nothing without
 * the school it belongs to, and a school with three engagements needs the
 * project to tell its rows apart — so the pair is what a reader actually uses,
 * and giving each half a column only spent width on repeating the client down
 * the page.
 *
 * <h2>The client a project name already carries</h2>
 *
 * <p>Provisioning names a project after its client — `Delhi Public School —
 * EDUNEXT-ERP` — while a hand-made one does not, so the same join would print
 * some schools twice and not others. Where the project name already opens with
 * the client's name, that prefix is dropped and what is left is the product;
 * where it does not, the whole name follows the client.
 */
export function projectLabel(row: Pick<ObMyTask, 'obClientName' | 'projectName'>): string {
  const client = (row.obClientName ?? '').trim()
  const project = projectSuffix((row.projectName ?? '').trim(), client)
  if (!client) return project
  return project ? `${client}${JOIN}${project}` : client
}

/** The project name with a client-name prefix taken off, if it had one. */
function projectSuffix(project: string, client: string): string {
  if (!client || !project.toLowerCase().startsWith(client.toLowerCase())) return project
  return project.slice(client.length).replace(LEADING_SEPARATOR, '').trim()
}

/**
 * `SIS — Configuration`: the module service and the step within it.
 *
 * <p>Same bargain as {@link projectLabel}. A step name is a stage label reused
 * across every service — half a queue reads "Configuration" — so it only means
 * something next to the service it configures.
 */
export function serviceStepLabel(row: Pick<ObMyTask, 'serviceName' | 'stepName'>): string {
  const service = (row.serviceName ?? '').trim()
  const step = (row.stepName ?? '').trim()
  if (!service) return step
  return step ? `${service}${JOIN}${step}` : service
}

/** `16 Sep 2026`, or an em dash for a task that has not activated. */
export function formatDueDate(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '—'
  return parsed.toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' })
}

/**
 * `The Shri Ram School — ERP · SIS · Communication · Task 1 of 1` — where the
 * task sits, in the words the project page's popup uses.
 *
 * <h2>The same crumb, so it is the same popup</h2>
 *
 * <p>The project page opens a task with
 * `service · step · Task N of M`, and this screen opened the same dialog with
 * `project · service · step` — the same component wearing a different label in
 * the one place a reader checks they are looking at what they think they are.
 * The tail is now the project page's, verbatim, including the position: a step
 * holding four tasks says which of the four this is.
 *
 * <h2>Why the project stays on the front</h2>
 *
 * <p>This queue spans every project the reader is on, and the row that says
 * which one is behind the scrim the moment the popup opens. On the project page
 * that segment would be the page's own title repeated; here it is the only
 * thing that says whose ERP this is.
 *
 * <p>The position is read off the journey's own steps — the ones sharing this
 * task's stage, in their own sequence — rather than off a count the row carries,
 * because it is the same list the project page numbers. Where the journey has
 * not landed or the stage matches nothing, the position is simply left off
 * rather than guessed at.
 */
export function taskCrumb(
  row: ObMyTask,
  steps: readonly ObJourneyStepView[] | undefined,
): string {
  const siblings = (steps ?? [])
    .filter((step) => step.stageKey === row.stepKey)
    .sort((a, b) => a.sequence - b.sequence)
  const index = siblings.findIndex((step) => step.id === row.taskId)
  const place = index >= 0 ? ` · Task ${index + 1} of ${siblings.length}` : ''
  return `${projectLabel(row)} · ${row.serviceName} · ${row.stepName}${place}`
}

/**
 * The focused page's task, assembled from the journey read and its row.
 *
 * <p>The journey read is what the project page already uses, so this reuses the
 * same cache entry and the same shape — which is what lets the focused page
 * hand `ObProjectTaskPanel` something it already knows how to draw instead of a
 * second task type that would drift from it.
 *
 * <p>Null where the journey does not carry the task: a task that was deleted
 * between the two reads, and the screen says so rather than rendering a panel
 * over nothing.
 */
export function focusedTask(
  row: ObMyTask,
  steps: readonly ObJourneyStepView[] | undefined,
): ProjectTask | null {
  const step = steps?.find((candidate) => candidate.id === row.taskId)
  if (!step) return null

  return {
    id: step.id,
    journeyId: row.journeyId,
    serviceName: row.serviceName,
    sequence: step.sequence,
    name: step.name,
    status: step.status,
    ownerUserId: step.ownerUserId,
    ownerIsInherited: step.ownerIsInherited ?? false,
    backupOwnerUserId: step.backupOwnerUserId,
    tatDays: step.tatDays ?? 0,
    requiresSignoff: step.requiresSignoff ?? false,
    dueAt: step.dueAt,
    tatUsedPercent: step.tatUsedPercent ?? null,
    /*
      The row's, not the step's. Both fold `implementation_stage_id` the same
      way and the contract says so — but the row is the one the grid printed,
      and a focused page whose step name disagreed with the row somebody clicked
      would be the more confusing of the two failures.
    */
    stageKey: row.stepKey,
    stageName: row.stepName,
    items: step.items ?? [],
    docs: step.docs ?? [],
  }
}
