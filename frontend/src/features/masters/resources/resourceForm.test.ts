import { describe, expect, it } from 'vitest'

import {
  emptyResourceForm,
  resourceFormSchema,
  splitFieldErrors,
  toFormValues,
  toWriteRequest,
  type ResourceFormValues,
} from './resourceForm'
import type { UserDetail } from '@/api/generated/model/userDetail'

/**
 * B-011 · the S-08 form's two translations and its validation.
 *
 * These are the parts worth testing on their own: the mapping between a form
 * and a request body is where a field silently stops being sent, and that is
 * unreachable behind a rendered page.
 */

const detail: UserDetail = {
  id: 3,
  displayName: 'Ravi Kumar',
  role: 'DEVELOPER',
  username: 'ravi',
  email: 'ravi@edunext.example',
  employeeCode: 'EMP-003',
  department: 'Engineering',
  designation: 'Senior Engineer',
  reportingManager: { id: 2, displayName: 'Meera Iyer' },
  projectIds: [1, 2],
  projects: [],
  isActive: true,
  openTicketCount: 4,
  lastLoginAt: '2026-08-10T06:30:00.000Z',
  createdAt: '2026-08-03T09:00:00.000Z',
  mobile: '+91 90000 00003',
  avatarUrl: null,
  dateOfJoining: '2025-04-15',
  location: 'Pune',
  timezone: 'Asia/Kolkata',
  dailyCapacityHrs: 8,
  weeklyOff: null,
  skills: ['Java', 'React'],
  projectAssignments: [
    { projectId: 1, roleInProject: 'DEVELOPER' },
    { projectId: 2 },
  ],
  mustChangePassword: false,
}

describe('toFormValues', () => {
  it('seeds every S-08 section from a loaded resource', () => {
    const values = toFormValues(detail)

    expect(values.employeeCode).toBe('EMP-003')
    expect(values.mobile).toBe('+91 90000 00003')
    expect(values.dateOfJoining).toBe('2025-04-15')
    expect(values.reportingManagerId).toBe(2)
    expect(values.location).toBe('Pune')
    expect(values.dailyCapacityHrs).toBe(8)
    expect(values.skills).toEqual(['Java', 'React'])
    expect(values.projects).toEqual([
      { projectId: 1, roleInProject: 'DEVELOPER' },
      // No role on the wire means "same as their global role", which the form
      // holds as ''. Undefined here would make the Select uncontrolled.
      { projectId: 2, roleInProject: '' },
    ])
  })

  /**
   * Every optional text input holds `''` when unset, never undefined — React
   * warns and switches the input from uncontrolled to controlled on the first
   * keystroke otherwise.
   */
  it('turns every null text field into an empty string, not undefined', () => {
    const values = toFormValues({ ...detail, mobile: null, location: null, dateOfJoining: null })

    expect(values.mobile).toBe('')
    expect(values.location).toBe('')
    expect(values.dateOfJoining).toBe('')
  })

  /** The one field where null is a value rather than an absence. */
  it('keeps weeklyOff null when it is null, and an array when it is one', () => {
    expect(toFormValues(detail).weeklyOff).toBeNull()
    expect(toFormValues({ ...detail, weeklyOff: [6, 7] }).weeklyOff).toEqual([6, 7])
    expect(toFormValues({ ...detail, weeklyOff: [] }).weeklyOff).toEqual([])
  })
})

describe('toWriteRequest', () => {
  const filled: ResourceFormValues = toFormValues(detail)

  it('sends every optional key, so a field the user cleared is actually cleared', () => {
    const body = toWriteRequest({ ...filled, mobile: '', location: '', department: '' })

    // Present and null — not absent. An absent key means "leave it alone" to
    // the server, so omitting these would silently keep the old value and the
    // form would appear not to save.
    expect(body).toHaveProperty('mobile', null)
    expect(body).toHaveProperty('location', null)
    expect(body).toHaveProperty('department', null)
  })

  it('trims text before sending', () => {
    const body = toWriteRequest({ ...filled, displayName: '  Ravi Kumar  ', department: '  Platform ' })

    expect(body.displayName).toBe('Ravi Kumar')
    expect(body.department).toBe('Platform')
  })

  it('sends weeklyOff null for inherit and [] for no weekly off', () => {
    expect(toWriteRequest({ ...filled, weeklyOff: null }).weeklyOff).toBeNull()
    expect(toWriteRequest({ ...filled, weeklyOff: [] }).weeklyOff).toEqual([])
  })

  it('drops a blank project role rather than sending an empty string the enum rejects', () => {
    const body = toWriteRequest({
      ...filled,
      projects: [
        { projectId: 1, roleInProject: 'QA' },
        { projectId: 2, roleInProject: '' },
      ],
    })

    expect(body.projects).toEqual([
      { projectId: 1, roleInProject: 'QA' },
      { projectId: 2, roleInProject: undefined },
    ])
  })

  it('round-trips a loaded resource without changing anything', () => {
    const body = toWriteRequest(toFormValues(detail))

    expect(body.displayName).toBe(detail.displayName)
    expect(body.employeeCode).toBe(detail.employeeCode)
    expect(body.email).toBe(detail.email)
    expect(body.username).toBe(detail.username)
    expect(body.role).toBe(detail.role)
    expect(body.reportingManagerId).toBe(2)
    expect(body.skills).toEqual(['Java', 'React'])
  })
})

describe('resourceFormSchema', () => {
  const valid: ResourceFormValues = {
    ...emptyResourceForm,
    employeeCode: 'EMP-100',
    displayName: 'New Person',
    email: 'new.person@edunext.example',
    username: 'new.person',
    role: 'DEVELOPER',
  }

  it('accepts the five required fields with everything else at its default', () => {
    expect(resourceFormSchema.safeParse(valid).success).toBe(true)
  })

  it.each([
    ['employeeCode', ''],
    ['displayName', ''],
    ['username', 'ab'],
    ['role', ''],
  ])('rejects a missing or too-short %s', (field, value) => {
    const result = resourceFormSchema.safeParse({ ...valid, [field]: value })
    expect(result.success).toBe(false)
  })

  it('rejects a malformed email', () => {
    expect(resourceFormSchema.safeParse({ ...valid, email: 'not-an-email' }).success).toBe(false)
  })

  /** Optional means optional — a blank must not fail the pattern. */
  it('accepts a blank mobile but rejects a malformed one', () => {
    expect(resourceFormSchema.safeParse({ ...valid, mobile: '' }).success).toBe(true)
    expect(resourceFormSchema.safeParse({ ...valid, mobile: 'call me' }).success).toBe(false)
  })

  it('accepts a blank profile photo but rejects a non-URL', () => {
    expect(resourceFormSchema.safeParse({ ...valid, avatarUrl: '' }).success).toBe(true)
    expect(resourceFormSchema.safeParse({ ...valid, avatarUrl: 'photo.png' }).success).toBe(false)
  })

  it('holds capacity between half an hour and a day', () => {
    expect(resourceFormSchema.safeParse({ ...valid, dailyCapacityHrs: 0 }).success).toBe(false)
    expect(resourceFormSchema.safeParse({ ...valid, dailyCapacityHrs: 25 }).success).toBe(false)
    expect(resourceFormSchema.safeParse({ ...valid, dailyCapacityHrs: 6.5 }).success).toBe(true)
  })

  /**
   * B-023's note records what a second day-numbering convention cost: a `0`
   * read as ISO makes Sunday a working day and every weekend-spanning SLA short
   * by a day. Refused here as well as at the column.
   */
  it('refuses day 0 — days are ISO 1=Mon … 7=Sun', () => {
    expect(resourceFormSchema.safeParse({ ...valid, weeklyOff: [0, 6] }).success).toBe(false)
    expect(resourceFormSchema.safeParse({ ...valid, weeklyOff: [6, 7] }).success).toBe(true)
  })
})

describe('splitFieldErrors', () => {
  it('routes a server error onto the field that caused it', () => {
    const { fields, unmatched } = splitFieldErrors({
      username: ['That username is already taken'],
      email: ['That email address is already registered'],
    })

    expect(fields).toEqual([
      { name: 'username', message: 'That username is already taken' },
      { name: 'email', message: 'That email address is already registered' },
    ])
    expect(unmatched).toEqual([])
  })

  /** An error nobody displays is worse than an ugly one. */
  it('hands back anything it cannot place, rather than dropping it', () => {
    const { fields, unmatched } = splitFieldErrors({ _: ['That record already exists'] })

    expect(fields).toEqual([])
    expect(unmatched).toEqual(['That record already exists'])
  })
})
