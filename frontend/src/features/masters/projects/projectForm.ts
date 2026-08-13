import { z } from 'zod'

import type { ProjectDetail } from '@/api/generated/model/projectDetail'
import type { ProjectPatchRequest } from '@/api/generated/model/projectPatchRequest'
import type { ProjectStatus } from '@/api/generated/model/projectStatus'
import type { ProjectWriteRequest } from '@/api/generated/model/projectWriteRequest'

/**
 * B-016 · the S-10 form's shape, its validation and its two translations.
 *
 * Kept out of the component for the reason `resourceForm.ts` and `ticketForm.ts`
 * both give: the mapping between a form and a request is the part worth testing
 * on its own, and it is unreachable behind a rendered page.
 *
 * <h2>Empty string is the form's null</h2>
 *
 * Every optional text input holds `''` when untouched, never `undefined` — React
 * would otherwise switch the input from uncontrolled to controlled on first
 * keystroke and warn. {@link toWriteRequest} and {@link toPatchRequest} are the
 * only places that turn `''` back into the `null` the contract means by "clear
 * it", so no component has to remember the convention.
 */

export const PROJECT_STATUSES: readonly { value: ProjectStatus; label: string; hint: string }[] = [
  { value: 'ACTIVE', label: 'Active', hint: 'Running. Appears everywhere.' },
  {
    value: 'ON_HOLD',
    label: 'On hold',
    hint: 'Paused. Still selectable — tickets can still be raised against it.',
  },
  {
    value: 'CLOSED',
    label: 'Closed',
    hint: 'Retired. Drops out of every picker; its tickets stay readable.',
  },
] as const

export const AUTO_ASSIGN_RULES = [
  { value: 'MANUAL', label: 'Manual' },
  { value: 'ROUND_ROBIN', label: 'Round robin' },
  { value: 'LEAST_LOADED', label: 'Least loaded' },
] as const

/**
 * The blueprint §12.1 palette, and the whole of the colour picker.
 *
 * A free hex input would let an Admin type `#FF00FF` — CLAUDE.md: "Never
 * introduce a colour that isn't a token." These eight are `--chart-1` … `--chart-8`
 * from `styles/tokens.css`, which is the set already used to colour a project
 * everywhere else it is rendered.
 *
 * **The server still accepts any `#RRGGBB`**, deliberately: constraining it to a
 * closed list server-side would mean the palette could not change without a
 * release, and this is a presentation decision. The rule is enforced where it is
 * a rule — in the UI that offers the choice.
 */
export const COLOUR_TAGS = [
  { value: '#4F46E5', label: 'Indigo' },
  { value: '#06B6D4', label: 'Cyan' },
  { value: '#10B981', label: 'Emerald' },
  { value: '#F59E0B', label: 'Amber' },
  { value: '#EF4444', label: 'Red' },
  { value: '#8B5CF6', label: 'Violet' },
  { value: '#EC4899', label: 'Pink' },
  { value: '#14B8A6', label: 'Teal' },
] as const

/** Matches `ProjectWriteRequest.projectCode`'s pattern, and the server's. */
export const PROJECT_CODE_PATTERN = /^[A-Z][A-Z0-9]{1,9}$/

/**
 * The field rules, before the cross-field one.
 *
 * Exported separately because `projectFormSchema` is a `ZodEffects` once
 * `.refine()` is applied, and a `ZodEffects` has no `.shape` — so the page's
 * "is this server error key a field on this form?" check would have to reach
 * into `_def`, which is orval-adjacent private API and breaks on a zod bump.
 */
export const projectFieldSchema = z
  .object({
    projectCode: z
      .string()
      .trim()
      .min(2, 'At least 2 characters')
      .max(10, 'At most 10 characters — it prefixes every ticket ID')
      .refine(
        (v) => PROJECT_CODE_PATTERN.test(v.toUpperCase()),
        'Letters and digits only, starting with a letter',
      ),
    name: z.string().trim().min(1, 'Project name is required').max(150),
    clientName: z.string().trim().max(150),
    description: z.string().trim().max(1000, 'At most 1000 characters'),
    projectManagerId: z.number().int().positive('A project manager is required'),
    colourTag: z.string(),
    startDate: z.string(),
    endDate: z.string(),
    status: z.enum(['ACTIVE', 'ON_HOLD', 'CLOSED']),
    autoAssignRule: z.enum(['MANUAL', 'ROUND_ROBIN', 'LEAST_LOADED']),
  })

// Two fields, so it cannot be a per-field rule — and the server checks the same
// thing, because a client-side date comparison is a convenience and not a
// guarantee.
export const projectFormSchema = projectFieldSchema.refine(
  (v) => !v.startDate || !v.endDate || v.endDate >= v.startDate,
  { path: ['endDate'], message: 'The target end date cannot be before the start date' },
)

export type ProjectFormValues = z.infer<typeof projectFieldSchema>

export const emptyProjectForm: ProjectFormValues = {
  projectCode: '',
  name: '',
  clientName: '',
  description: '',
  // 0 rather than null: the picker is a controlled `<select>` and null would
  // make it uncontrolled. `positive()` rejects it, so an untouched picker fails
  // validation rather than posting a falsy id.
  projectManagerId: 0,
  colourTag: COLOUR_TAGS[0].value,
  startDate: '',
  endDate: '',
  status: 'ACTIVE',
  autoAssignRule: 'MANUAL',
}

/** The edit form's load. Every null becomes `''`; see the note at the top. */
export function toFormValues(project: ProjectDetail): ProjectFormValues {
  return {
    projectCode: project.projectCode,
    name: project.name,
    clientName: project.clientName ?? '',
    description: project.description ?? '',
    projectManagerId: project.projectManager?.id ?? 0,
    colourTag: project.colourTag ?? COLOUR_TAGS[0].value,
    startDate: project.startDate ?? '',
    endDate: project.endDate ?? '',
    status: project.status ?? 'ACTIVE',
    autoAssignRule: project.autoAssignRule ?? 'MANUAL',
  }
}

export function toWriteRequest(values: ProjectFormValues): ProjectWriteRequest {
  return {
    projectCode: values.projectCode.trim().toUpperCase(),
    name: values.name.trim(),
    clientName: blankToNull(values.clientName),
    description: blankToNull(values.description),
    projectManagerId: values.projectManagerId,
    colourTag: values.colourTag,
    startDate: blankToNull(values.startDate),
    endDate: blankToNull(values.endDate),
    status: values.status,
    autoAssignRule: values.autoAssignRule,
  }
}

/**
 * The edit save.
 *
 * **`projectCode` is sent whether or not it changed**, and that is safe by
 * design rather than by luck: the server treats the same code as a no-op and
 * only refuses a *different* one on a project that has issued a ticket ID. The
 * form disables the input in that case anyway, so the value it sends is the
 * stored one.
 *
 * Everything else is sent too. S-10 is a whole-form save, not a dirty-field
 * diff, and a partial patch assembled from React state is a source of "the
 * field I did not touch got cleared" bugs that only appear under a race.
 */
export function toPatchRequest(values: ProjectFormValues): ProjectPatchRequest {
  return toWriteRequest(values) as ProjectPatchRequest
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

/**
 * Whether the code input is editable.
 *
 * The rule mirrors the server's exactly: `ticketsIssued > 0` closes it, and
 * nothing else does. On a create there is no project yet, so it is always open.
 */
export function isCodeEditable(project: ProjectDetail | null): boolean {
  return project == null || (project.ticketsIssued ?? 0) === 0
}
