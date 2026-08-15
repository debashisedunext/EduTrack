import type { AutoAssignRule } from '@/api/generated/model/autoAssignRule'
import type { ProjectSettings } from '@/api/generated/model/projectSettings'
import type { ProjectSettingsTaskType } from '@/api/generated/model/projectSettingsTaskType'
import type { ProjectSettingsWrite } from '@/api/generated/model/projectSettingsWrite'
import type { TicketFieldCode } from '@/api/generated/model/ticketFieldCode'

/**
 * B-019 · the Settings tab's decisions, with no React in them.
 *
 * The same split `slaMatrix.ts` and `projectTeam.ts` use: everything that can be
 * got wrong here is bookkeeping — what a cleared checkbox list means, what gets
 * sent, whether anything has actually changed — so it lives where it can be
 * tested without rendering anything.
 */

// ---------------------------------------------------------------------------
// the vocabulary, in words a person can answer
// ---------------------------------------------------------------------------

export const AUTO_ASSIGN_RULES: ReadonlyArray<{
  value: AutoAssignRule
  label: string
  hint: string
}> = [
  {
    value: 'MANUAL',
    label: 'Manual',
    hint: 'A new ticket stays unassigned until somebody picks it up.',
  },
  {
    value: 'ROUND_ROBIN',
    label: 'Round robin',
    hint: 'Each new ticket goes to the next member of the project team in turn.',
  },
  {
    value: 'LEAST_LOADED',
    label: 'Least loaded',
    hint: 'Each new ticket goes to the team member with the fewest open tickets.',
  },
]

/**
 * The ticket fields a project may require, labelled as the create form labels
 * them.
 *
 * **This list is the contract's `TicketFieldCode` and must stay in step with
 * it** — it is exactly the optional fields of `TicketCreateRequest`, and the
 * fields every ticket already requires are deliberately absent. A checkbox for
 * `title` could not change any outcome, and a control that cannot do what it
 * appears to do is worse than a missing one.
 *
 * The `hint` on `MODULE` is not decoration: §7.5 already makes the field
 * mandatory on the form for bug-type task types, so an administrator ticking it
 * here is tightening a rule rather than inventing one, and the screen should say
 * which of the two they are doing.
 */
export const TICKET_FIELDS: ReadonlyArray<{
  value: TicketFieldCode
  label: string
  hint?: string
}> = [
  { value: 'DESCRIPTION', label: 'Description' },
  {
    value: 'MODULE',
    label: 'Module',
    hint: 'Already required for bug-type task types — ticking this requires it for all of them.',
  },
  { value: 'SCREEN_NAME', label: 'Screen name' },
  { value: 'FEATURE', label: 'Feature' },
  { value: 'STEPS_TO_GENERATE', label: 'Steps to generate' },
  { value: 'CLIENT', label: 'Client' },
  { value: 'CLIENT_CONTACT', label: 'Client contact' },
  { value: 'ASSIGNEE', label: 'Assignee' },
  { value: 'ESTIMATED_HRS', label: 'Estimated hours' },
  { value: 'PLANNED_CLOSE_DATE', label: 'Planned close date' },
]

// ---------------------------------------------------------------------------
// the draft
// ---------------------------------------------------------------------------

/**
 * What the user has changed, before it is worth sending.
 *
 * Sets rather than arrays, because every operation the screen performs on them
 * is a membership test or a toggle, and an array turns each of those into a
 * scan. They are converted to arrays once, on save, in the order the server
 * sent — so a project's stored list does not reshuffle itself because somebody
 * unticked and reticked a box.
 */
export interface SettingsDraft {
  autoAssignRule: AutoAssignRule
  mandatoryFields: Set<TicketFieldCode>
  allowedTaskTypeIds: Set<number>
}

/**
 * The draft a screen starts in.
 *
 * **An unrestricted project starts with no boxes ticked**, not with all of them.
 * The server answers `isAllowed: true` for every row when `restrictsTaskTypes`
 * is false — because that is what unrestricted means — but seeding the draft
 * from those flags would turn the first save into an allow-list naming every
 * task type that happened to exist that day, and the twelfth type an Admin adds
 * next month would then be silently barred on this project. The one bit that
 * distinguishes the two states is `restrictsTaskTypes`, and this is the line
 * that respects it.
 */
export function draftFor(settings: ProjectSettings): SettingsDraft {
  return {
    autoAssignRule: settings.autoAssignRule,
    mandatoryFields: new Set(settings.mandatoryFields),
    allowedTaskTypeIds: new Set(
      settings.restrictsTaskTypes
        ? settings.taskTypes.filter((t) => t.isAllowed).map((t) => t.taskTypeId)
        : [],
    ),
  }
}

/** Toggling a member of a set, without mutating the one React is holding. */
export function toggle<T>(set: Set<T>, value: T, next: boolean): Set<T> {
  const copy = new Set(set)
  if (next) copy.add(value)
  else copy.delete(value)
  return copy
}

// ---------------------------------------------------------------------------
// what the screen says about the state it is in
// ---------------------------------------------------------------------------

/**
 * Whether an empty allow-list is showing, and therefore whether the screen owes
 * the user a sentence.
 *
 * This is the whole hazard of the tab in one boolean. Eleven unticked boxes look
 * exactly like "nothing may be raised on this project", and they mean the
 * opposite: no allow-list at all, so every active task type is permitted. The
 * screen has to say so in words — there is no arrangement of checkboxes that
 * says it on its own.
 */
export const isUnrestricted = (draft: SettingsDraft) => draft.allowedTaskTypeIds.size === 0

/**
 * A summary of the allow-list, in a sentence rather than a count.
 *
 * "0 of 11 selected" is the one phrasing to avoid: it is accurate, and it reads
 * as a restriction that permits nothing.
 */
export function allowListSummary(draft: SettingsDraft, taskTypes: ProjectSettingsTaskType[]): string {
  if (isUnrestricted(draft)) {
    return `No restriction — every active task type may be raised on this project.`
  }
  const active = taskTypes.filter((t) => t.isActive).length
  return `${draft.allowedTaskTypeIds.size} of ${active} task types may be raised on this project.`
}

/**
 * Task types this project allows that the Task Type Master has since retired.
 *
 * Rendered separately and labelled, because they cannot be raised on a new
 * ticket whatever this screen says — but they must still be rendered, since the
 * `PUT` is assembled from these rows and one that was allowed and not shown
 * would be dropped by the next save through a screen that never displayed it.
 */
export const retiredAllowed = (taskTypes: ProjectSettingsTaskType[]) =>
  taskTypes.filter((t) => !t.isActive)

// ---------------------------------------------------------------------------
// dirty state and the request
// ---------------------------------------------------------------------------

export function isDirty(settings: ProjectSettings, draft: SettingsDraft): boolean {
  const original = draftFor(settings)
  return (
    original.autoAssignRule !== draft.autoAssignRule
    || !sameSet(original.mandatoryFields, draft.mandatoryFields)
    || !sameSet(original.allowedTaskTypeIds, draft.allowedTaskTypeIds)
  )
}

function sameSet<T>(a: Set<T>, b: Set<T>): boolean {
  if (a.size !== b.size) return false
  for (const value of a) {
    if (!b.has(value)) return false
  }
  return true
}

/**
 * The body of the `PUT`.
 *
 * All three fields, always — the operation is a wholesale replace and an omitted
 * field on a replace is ambiguous between "leave it alone" and "clear it".
 *
 * Both lists are emitted **in the order the server sent**, not in the order the
 * user clicked: `mandatoryFields` follows `TICKET_FIELDS` and the allow-list
 * follows `settings.taskTypes`, which is the Task Type Master's own `seq`. A
 * request whose array order depends on click order makes two saves of one state
 * look like two different states in a log.
 */
export function toWriteRequest(
  settings: ProjectSettings,
  draft: SettingsDraft,
): ProjectSettingsWrite {
  return {
    autoAssignRule: draft.autoAssignRule,
    mandatoryFields: TICKET_FIELDS
      .map((f) => f.value)
      .filter((code) => draft.mandatoryFields.has(code)),
    // An empty array here is not a degenerate request — it is how the
    // restriction is removed, and the only way to remove it.
    allowedTaskTypeIds: settings.taskTypes
      .map((t) => t.taskTypeId)
      .filter((id) => draft.allowedTaskTypeIds.has(id)),
  }
}
