import type { Level } from '@/api/generated/model/level'
import type { TaskType } from '@/api/generated/model/taskType'
import type { TaskTypePatchRequest } from '@/api/generated/model/taskTypePatchRequest'
import type { TaskTypeWriteRequest } from '@/api/generated/model/taskTypeWriteRequest'

/**
 * S-11 Task Type Master — form state, validation and the mapping onto the wire.
 * B-020.
 *
 * Kept apart from the page for the reason `projectForm.ts` gives: the rules are
 * worth testing without rendering anything, and the two mappers are where a
 * quiet mistake would show up as a save that silently did nothing.
 */

/**
 * Blueprint §12.1's chart palette, colour-blind safe.
 *
 * CLAUDE.md: "Design tokens come from blueprint §12.1. Never introduce a colour
 * that isn't a token." A free-text hex input would let an Admin add a twelfth
 * task type in a colour the design system does not contain — and it would then
 * appear in the Task Type Distribution donut beside eight that do. The server
 * only checks the *shape* (`#RRGGBB`), because it has no palette; the palette
 * lives here, where the choice is made.
 *
 * These are the same eight B-002 cycled through when it seeded the eleven, so a
 * seeded type's colour is always one of the swatches this screen offers.
 */
export const CHART_PALETTE: readonly string[] = [
  '#4F46E5',
  '#06B6D4',
  '#10B981',
  '#F59E0B',
  '#EF4444',
  '#8B5CF6',
  '#EC4899',
  '#14B8A6',
]

/** The four the contract's `Level` enum can carry — see `TaskTypeService`. */
export const LEVELS: readonly Level[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

/**
 * What the create and edit dialogs hold.
 *
 * `defaultSlaHrs` is a string because that is what an `<input>` gives back, and
 * an empty numeric input yielding `NaN` is the classic way a required-field
 * error turns into "expected number" — `ticketForm.ts` makes the same call.
 * Empty means *no default SLA*, which is a real state: the type falls through
 * to the priority default on the §6 ladder.
 */
export interface TaskTypeFormValues {
  code: string
  name: string
  icon: string
  colour: string
  defaultLevel: Level
  defaultSlaHrs: string
  seq: string
  isActive: boolean
}

export const emptyTaskTypeForm: TaskTypeFormValues = {
  code: '',
  name: '',
  icon: '',
  colour: CHART_PALETTE[0],
  defaultLevel: 'MEDIUM',
  defaultSlaHrs: '',
  seq: '',
  isActive: true,
}

/** The stored row as the edit dialog first renders it. */
export function toFormValues(type: TaskType): TaskTypeFormValues {
  return {
    code: type.code ?? '',
    name: type.name ?? '',
    icon: type.icon ?? '',
    colour: type.colour ?? CHART_PALETTE[0],
    defaultLevel: type.defaultLevel ?? 'MEDIUM',
    defaultSlaHrs: type.defaultSlaHrs == null ? '' : String(type.defaultSlaHrs),
    seq: type.seq == null ? '' : String(type.seq),
    isActive: type.isActive ?? true,
  }
}

/**
 * The same rules the server enforces, so the form refuses before the round
 * trip rather than after it.
 *
 * The server stays the authority — these are duplicated deliberately and
 * narrowly, and the two that are *not* here are the ones a browser cannot know:
 * uniqueness of `code` and of `name`. Both come back as a field-keyed 409 and
 * land on the input.
 */
export function taskTypeFormErrors(
  values: TaskTypeFormValues,
): Partial<Record<keyof TaskTypeFormValues, string>> {
  const errors: Partial<Record<keyof TaskTypeFormValues, string>> = {}

  if (!values.code.trim()) {
    errors.code = 'A code is required.'
  } else if (!/^[A-Za-z][A-Za-z0-9_]{1,39}$/.test(values.code.trim())) {
    errors.code = 'Start with a letter, then letters, digits and underscores only.'
  }

  if (!values.name.trim()) {
    errors.name = 'A name is required.'
  } else if (values.name.trim().length > 80) {
    errors.name = 'At most 80 characters.'
  }

  if (!/^#[0-9A-Fa-f]{6}$/.test(values.colour)) {
    errors.colour = 'Pick a colour from the palette.'
  }

  if (values.defaultSlaHrs.trim()) {
    const hours = Number(values.defaultSlaHrs)
    if (!Number.isFinite(hours) || hours < 0) {
      errors.defaultSlaHrs = 'Hours must be zero or more, or blank for no default.'
    }
  }

  if (values.seq.trim()) {
    const seq = Number(values.seq)
    if (!Number.isInteger(seq) || seq < 0 || seq > 32767) {
      errors.seq = 'A whole number between 0 and 32767, or blank to add at the end.'
    }
  }

  return errors
}

export function toWriteRequest(values: TaskTypeFormValues): TaskTypeWriteRequest {
  return {
    code: values.code.trim().toUpperCase(),
    name: values.name.trim(),
    icon: values.icon.trim() || null,
    colour: values.colour,
    defaultLevel: values.defaultLevel,
    defaultSlaHrs: values.defaultSlaHrs.trim() ? Number(values.defaultSlaHrs) : null,
    seq: values.seq.trim() ? Number(values.seq) : null,
    isActive: values.isActive,
  }
}

/**
 * The whole form on every save, `code` included.
 *
 * Sending the stored code is a deliberate no-op on the server — S-11 is a
 * full-form submit, and any other reading would make every edit a 409. Sending
 * it is what makes a *changed* one refusable, which is the point: a caller who
 * believed they had renamed the code must not be told the save succeeded.
 *
 * `icon` and `defaultSlaHrs` go as explicit `null` when blank rather than being
 * omitted, which is how the server is asked to clear them — the distinction its
 * patch DTO is a POJO in order to keep.
 */
export function toPatchRequest(values: TaskTypeFormValues): TaskTypePatchRequest {
  return {
    code: values.code.trim().toUpperCase(),
    name: values.name.trim(),
    icon: values.icon.trim() || null,
    colour: values.colour,
    defaultLevel: values.defaultLevel,
    defaultSlaHrs: values.defaultSlaHrs.trim() ? Number(values.defaultSlaHrs) : null,
    seq: values.seq.trim() ? Number(values.seq) : null,
    isActive: values.isActive,
  }
}
