import type { Level } from '@/api/generated/model/level'
import type { Priority } from '@/api/generated/model/priority'
import type { PriorityPatchRequest } from '@/api/generated/model/priorityPatchRequest'
import type { PriorityWriteRequest } from '@/api/generated/model/priorityWriteRequest'

/**
 * S-12 Priority / Level Master — form state, validation and the mapping onto
 * the wire. B-021.
 *
 * Kept apart from the page for the reason `projectForm.ts` and `taskTypeForm.ts`
 * give: the rules are worth testing without rendering anything, and the two
 * mappers are where a quiet mistake would show up as a save that silently did
 * nothing.
 */

/**
 * Blueprint §12.1's **level chips** first, then the rest of its chart palette.
 *
 * CLAUDE.md: "Design tokens come from blueprint §12.1. Never introduce a colour
 * that isn't a token." The four chip hexes lead because they are the colours
 * §12.1 states for exactly this purpose and exactly these four rows — they are
 * what B-002 seeds, and what the MSW mock disagreed with until B-021. The chart
 * palette follows so that a fifth level, on the day one becomes possible, has
 * somewhere to go that is still a token.
 *
 * The server only checks the *shape* (`#RRGGBB`), because it has no palette;
 * the palette lives here, where the choice is made.
 */
export const LEVEL_CHIP_COLOURS: readonly string[] = [
  '#10B981', // Low
  '#3B82F6', // Medium
  '#F59E0B', // High
  '#EF4444', // Critical
]

export const PRIORITY_PALETTE: readonly string[] = [
  ...LEVEL_CHIP_COLOURS,
  '#4F46E5',
  '#06B6D4',
  '#8B5CF6',
  '#EC4899',
  '#14B8A6',
]

/**
 * The four the contract's `Level` enum can carry — see `PriorityService`.
 *
 * S-12 says an Admin can add further levels without a release, and this array
 * is why that is not yet true: `Level` types seven ticket and SLA schemas, and
 * a fifth code would serialise into a response the generated client's own zod
 * schema rejects. The create dialog offers the codes not already taken, which
 * in a seeded system is none — so the create button exists for the case where
 * one has been retired and somebody wants a different code, and the honest
 * empty state says so rather than presenting a free-text box that always fails.
 */
export const CONTRACT_LEVELS: readonly Level[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

/**
 * What the create and edit dialogs hold.
 *
 * `defaultSlaHrs` is a string because that is what an `<input>` gives back, and
 * an empty numeric input yielding `NaN` is the classic way a required-field
 * error turns into "expected number" — `ticketForm.ts` makes the same call.
 * Empty means *no default SLA*, which is a real state: the level contributes no
 * rung 4 to the §6 ladder and resolution falls through to the task type.
 */
export interface PriorityFormValues {
  level: Level
  name: string
  colour: string
  defaultSlaHrs: string
  autoEscalates: boolean
  seq: string
  isActive: boolean
}

export const emptyPriorityForm: PriorityFormValues = {
  level: 'MEDIUM',
  name: '',
  colour: LEVEL_CHIP_COLOURS[1],
  defaultSlaHrs: '',
  autoEscalates: false,
  seq: '',
  isActive: true,
}

/** The stored row as the edit dialog first renders it. */
export function toFormValues(priority: Priority): PriorityFormValues {
  return {
    level: priority.level ?? 'MEDIUM',
    name: priority.name ?? '',
    colour: priority.colour ?? LEVEL_CHIP_COLOURS[1],
    defaultSlaHrs: priority.defaultSlaHrs == null ? '' : String(priority.defaultSlaHrs),
    autoEscalates: priority.autoEscalates ?? false,
    seq: priority.seq == null ? '' : String(priority.seq),
    isActive: priority.isActive ?? true,
  }
}

/**
 * The same rules the server enforces, so the form refuses before the round trip
 * rather than after it.
 *
 * The server stays the authority — these are duplicated deliberately and
 * narrowly, and the ones that are *not* here are the ones a browser cannot
 * know: uniqueness of `level` and of `name`, whether active task types block a
 * retire, and whether this is the last escalation target. All four come back as
 * a field-keyed 409 and land on the input.
 */
export function priorityFormErrors(
  values: PriorityFormValues,
): Partial<Record<keyof PriorityFormValues, string>> {
  const errors: Partial<Record<keyof PriorityFormValues, string>> = {}

  if (!values.name.trim()) {
    errors.name = 'A name is required.'
  } else if (values.name.trim().length > 40) {
    errors.name = 'At most 40 characters.'
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

  // A retired level cannot be the escalation target, and the form says so
  // rather than letting the server say it: §6 promotes an overdue ticket *to*
  // this level, and a level no picker offers is one nobody could then change.
  if (!values.isActive && values.autoEscalates) {
    errors.autoEscalates = 'A retired level cannot be the escalation target.'
  }

  return errors
}

export function toWriteRequest(values: PriorityFormValues): PriorityWriteRequest {
  return {
    level: values.level,
    name: values.name.trim(),
    colour: values.colour,
    defaultSlaHrs: values.defaultSlaHrs.trim() ? Number(values.defaultSlaHrs) : null,
    autoEscalates: values.autoEscalates,
    seq: values.seq.trim() ? Number(values.seq) : null,
    isActive: values.isActive,
  }
}

/**
 * The whole form on every save, `level` included.
 *
 * Sending the stored code is a deliberate no-op on the server — S-12 is a
 * full-form submit, and any other reading would make every edit a 409. Sending
 * it is what makes a *changed* one refusable, which is the point: `tickets.level`
 * stores this string with no foreign key behind it, so a rename would not
 * cascade, it would orphan.
 *
 * **`autoEscalates` is sent only when it is `true`.** Sending `false` on every
 * save would make an ordinary rename of an unflagged level carry a clear the
 * server has to evaluate, and — worse — would let the form express a state the
 * server refuses: clearing the last flag leaves §6 with no target. So turning
 * it *off* is not an action this screen offers. The control is checked and
 * disabled on the level that currently holds it, and moving the target is a
 * `true` sent on the level it is moving to, which clears the incumbent
 * server-side. `PriorityListPage` is where that is rendered; this is the half
 * that makes an "off" unreachable rather than merely discouraged.
 */
export function toPatchRequest(values: PriorityFormValues): PriorityPatchRequest {
  return {
    level: values.level,
    name: values.name.trim(),
    colour: values.colour,
    defaultSlaHrs: values.defaultSlaHrs.trim() ? Number(values.defaultSlaHrs) : null,
    ...(values.autoEscalates ? { autoEscalates: true } : {}),
    seq: values.seq.trim() ? Number(values.seq) : null,
    isActive: values.isActive,
  }
}
