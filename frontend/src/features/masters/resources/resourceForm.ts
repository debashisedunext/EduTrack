import { z } from 'zod'

import type { RoleCode } from '@/api/generated/model/roleCode'
import type { ProjectRoleCode } from '@/api/generated/model/projectRoleCode'
import type { UserDetail } from '@/api/generated/model/userDetail'
import type { UserWriteRequest } from '@/api/generated/model/userWriteRequest'

/**
 * B-011 · the S-08 form's shape, its validation and its two translations.
 *
 * Kept out of the component for the reason `ticketForm.ts` gives: the mapping
 * between a form and a request is the part that is worth testing on its own,
 * and it is unreachable behind a rendered page.
 *
 * <h2>Empty string is the form's null</h2>
 *
 * Every optional text input holds `''` when untouched, never `undefined` —
 * React would otherwise switch the input from uncontrolled to controlled on
 * first keystroke and warn. {@link toWriteRequest} is the single place that
 * turns `''` back into the `null` the contract means by "clear it", so no
 * component has to remember the convention.
 */

export const PROJECT_ROLES: readonly ProjectRoleCode[] = [
  'PM',
  'DEVELOPER',
  'SUPPORT',
  'QA',
  'DEPLOYMENT',
  'VIEWER',
] as const

/** Mon…Sun as ISO day numbers. 1=Mon … 7=Sun, never JavaScript's Sunday-zero. */
export const ISO_DAYS = [
  { value: 1, label: 'Mon' },
  { value: 2, label: 'Tue' },
  { value: 3, label: 'Wed' },
  { value: 4, label: 'Thu' },
  { value: 5, label: 'Fri' },
  { value: 6, label: 'Sat' },
  { value: 7, label: 'Sun' },
] as const

const projectAssignmentSchema = z.object({
  projectId: z.number().int().positive(),
  roleInProject: z.enum(['PM', 'DEVELOPER', 'SUPPORT', 'QA', 'DEPLOYMENT', 'VIEWER', '']),
})

export const resourceFormSchema = z.object({
  // ── Personal ──────────────────────────────────────────────────────────────
  employeeCode: z.string().trim().min(1, 'Employee code is required').max(20),
  displayName: z.string().trim().min(1, 'Full name is required').max(120),
  email: z.string().trim().min(1, 'Email is required').email('That is not a valid email address').max(150),
  mobile: z
    .string()
    .trim()
    .max(20)
    .refine((v) => v === '' || /^[0-9+][0-9 ()-]{5,19}$/.test(v), 'Use digits, spaces, brackets or dashes'),
  avatarUrl: z
    .string()
    .trim()
    .max(500)
    .refine((v) => v === '' || /^https?:\/\//.test(v), 'Must be an http or https URL'),
  dateOfJoining: z.string(),

  // ── Access ────────────────────────────────────────────────────────────────
  username: z.string().trim().min(3, 'At least 3 characters').max(50),
  role: z.string().min(1, 'Role is required'),
  isActive: z.boolean(),

  // ── Org ───────────────────────────────────────────────────────────────────
  department: z.string().trim().max(80),
  designation: z.string().trim().max(80),
  reportingManagerId: z.number().int().positive().nullable(),
  location: z.string().trim().max(120),
  timezone: z.string().trim().min(1, 'Time zone is required').max(50),

  // ── Work ──────────────────────────────────────────────────────────────────
  dailyCapacityHrs: z
    .number({ invalid_type_error: 'Enter a number of hours' })
    .min(0.5, 'At least half an hour')
    .max(24, 'A day has 24 hours'),
  /**
   * `null` is "inherit the org working week"; `[]` is "no weekly off at all".
   * They are different answers and the form must be able to express both — see
   * the note on `weeklyOff` in the contract.
   */
  weeklyOff: z.array(z.number().int().min(1).max(7)).nullable(),
  skills: z.array(z.string().trim().min(1).max(60)).max(30, '30 skills is plenty'),

  // ── Projects ──────────────────────────────────────────────────────────────
  projects: z.array(projectAssignmentSchema).max(100),
})

export type ResourceFormValues = z.infer<typeof resourceFormSchema>

/** S-08's stated defaults, for a create. */
export const emptyResourceForm: ResourceFormValues = {
  employeeCode: '',
  displayName: '',
  email: '',
  mobile: '',
  avatarUrl: '',
  dateOfJoining: '',
  username: '',
  role: '',
  isActive: true,
  department: '',
  designation: '',
  reportingManagerId: null,
  location: '',
  timezone: 'Asia/Kolkata',
  dailyCapacityHrs: 8,
  weeklyOff: null,
  skills: [],
  projects: [],
}

/**
 * Seeds the form from a loaded resource.
 *
 * Null becomes `''` for text and stays null for `weeklyOff`, which is the one
 * field where null carries meaning rather than absence.
 */
export function toFormValues(resource: UserDetail): ResourceFormValues {
  return {
    employeeCode: resource.employeeCode ?? '',
    displayName: resource.displayName,
    email: resource.email ?? '',
    mobile: resource.mobile ?? '',
    avatarUrl: resource.avatarUrl ?? '',
    dateOfJoining: resource.dateOfJoining ?? '',
    username: resource.username ?? '',
    role: resource.role ?? '',
    isActive: resource.isActive ?? true,
    department: resource.department ?? '',
    designation: resource.designation ?? '',
    reportingManagerId: resource.reportingManager?.id ?? null,
    location: resource.location ?? '',
    timezone: resource.timezone ?? 'Asia/Kolkata',
    dailyCapacityHrs: resource.dailyCapacityHrs ?? 8,
    weeklyOff: resource.weeklyOff ?? null,
    skills: resource.skills ?? [],
    projects: (resource.projectAssignments ?? []).map((a) => ({
      projectId: a.projectId,
      roleInProject: (a.roleInProject ?? '') as ProjectRoleCode | '',
    })),
  }
}

/**
 * The form as a request body.
 *
 * **Every optional key is always sent**, as either a value or an explicit
 * `null`. The contract's third state — absent, meaning "leave it alone" —
 * exists for callers patching one field, and this form is not one of them: it
 * holds the whole resource, so anything it omitted would be a field the user
 * cleared and the server silently kept. Sending null is what makes the clear
 * actually happen.
 */
export function toWriteRequest(values: ResourceFormValues): UserWriteRequest {
  return {
    displayName: values.displayName.trim(),
    employeeCode: values.employeeCode.trim(),
    email: values.email.trim(),
    username: values.username.trim(),
    role: values.role as RoleCode,

    mobile: blankToNull(values.mobile),
    avatarUrl: blankToNull(values.avatarUrl),
    dateOfJoining: blankToNull(values.dateOfJoining),

    isActive: values.isActive,

    department: blankToNull(values.department),
    designation: blankToNull(values.designation),
    reportingManagerId: values.reportingManagerId,
    location: blankToNull(values.location),
    timezone: values.timezone.trim(),

    dailyCapacityHrs: values.dailyCapacityHrs,
    weeklyOff: values.weeklyOff,
    skills: values.skills,

    projects: values.projects.map((assignment) => ({
      projectId: assignment.projectId,
      // '' is the form's "no specific role on this project", which the column
      // stores as NULL — the membership still exists, it just does not override
      // the person's global role.
      roleInProject: assignment.roleInProject === '' ? undefined : assignment.roleInProject,
    })),
  }
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

/**
 * Maps a `400`/`409` problem document's field keys onto form field names.
 *
 * The server names the request's fields and the form's names match, with one
 * exception: `reportingManagerId` errors are raised against the picker, and
 * `projects` against the whole section rather than a row. Anything unrecognised
 * comes back so the caller can show it in a banner instead of dropping it —
 * an error nobody displays is worse than an ugly one.
 */
export function splitFieldErrors(
  errors: Record<string, string[]>,
): { fields: Array<{ name: keyof ResourceFormValues; message: string }>; unmatched: string[] } {
  const known = new Set(Object.keys(resourceFormSchema.shape))
  const fields: Array<{ name: keyof ResourceFormValues; message: string }> = []
  const unmatched: string[] = []

  for (const [key, messages] of Object.entries(errors)) {
    const message = messages[0] ?? 'Invalid'
    if (known.has(key)) {
      fields.push({ name: key as keyof ResourceFormValues, message })
    } else {
      unmatched.push(message)
    }
  }
  return { fields, unmatched }
}
