import { describe, expect, it } from 'vitest'

import type { Me } from '@/api/generated/model/me'
import type { ObJourneyStepView } from '@/api/generated/model/obJourneyStepView'
import type { ObMyTask } from '@/api/generated/model/obMyTask'

import {
  focusedTask,
  formatDueDate,
  isObImplementor,
  myTaskDotLabel,
  myTaskDotState,
  myTaskStatusLabel,
  projectLabel,
  serviceStepLabel,
} from './myTasks'

/**
 * My Tasks' own vocabulary.
 *
 * <p>The access check is the one worth the most care: it decides whether a
 * screen appears, and the role it reads for — `OB_STEP_OWNER` — is not called
 * "implementor" anywhere in the data, only on the designer's own column header.
 */

function me(over: Partial<Me> = {}): Me {
  return {
    id: 41,
    displayName: 'Kavya Sharma',
    role: 'DEVELOPER',
    username: 'kavya',
    email: 'kavya@example.com',
    permissions: [],
    projectIds: [],
    reporteeIds: [],
    timezone: 'Asia/Kolkata',
    modules: ['ONBOARDING'],
    ...over,
  } as Me
}

describe('isObImplementor', () => {
  it('is true for the module role that owns onboarding tasks', () => {
    expect(isObImplementor(me({ moduleRoles: { ONBOARDING: 'OB_STEP_OWNER' } }))).toBe(true)
  })

  /**
   * An OB Admin runs the module; they are not the person the queue is for. Two
   * separate grants, and this reads only the one it names.
   */
  it('is false for the other onboarding roles', () => {
    expect(isObImplementor(me({ moduleRoles: { ONBOARDING: 'OB_ADMIN' } }))).toBe(false)
    expect(isObImplementor(me({ moduleRoles: { ONBOARDING: 'OB_MANAGER' } }))).toBe(false)
    expect(isObImplementor(me({ moduleRoles: { ONBOARDING: 'OB_SALES' } }))).toBe(false)
    expect(isObImplementor(me({ moduleRoles: { ONBOARDING: 'OB_VIEWER' } }))).toBe(false)
  })

  /** A step owner in another module is not one here. */
  it('reads the onboarding grant and not another module’s', () => {
    expect(isObImplementor(me({ moduleRoles: { TICKETING: 'OB_STEP_OWNER' } }))).toBe(false)
  })

  /**
   * A session issued before the claim was exposed carries no `moduleRoles` at
   * all. Hiding the row is the right direction of the error — the endpoint
   * returns only the caller's own tasks either way.
   */
  it('is false when the session carries no module roles at all', () => {
    expect(isObImplementor(me())).toBe(false)
    expect(isObImplementor(undefined)).toBe(false)
    expect(isObImplementor(null)).toBe(false)
  })
})

describe('myTaskStatusLabel', () => {
  it('speaks the product’s words, not the enum’s', () => {
    expect(myTaskStatusLabel('PENDING')).toBe('Not started')
    expect(myTaskStatusLabel('WAITING_ON_CLIENT')).toBe('Waiting on client')
    expect(myTaskStatusLabel('SKIPPED')).toBe('Waived')
  })

  /** A status added server-side shows as itself rather than as blank. */
  it('falls back to the raw value for a status it does not know', () => {
    expect(myTaskStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })
})

/**
 * The queue's status column: four colours, read off the status and the
 * server's own overdue flag — the task strip's reading, shared rather than
 * copied, so the same task cannot be red on its project page and grey here.
 */
describe('myTaskDotState', () => {
  const dot = (status: string, isOverdue = false) =>
    myTaskDotState({ status, isOverdue } as Pick<ObMyTask, 'status' | 'isOverdue'>)

  it('is Pending for a task nobody has started', () => {
    expect(dot('PENDING')).toBe('PENDING')
  })

  /**
   * Both are states a task reaches by being worked on — the same reading the
   * Module strip's partial bucket takes.
   */
  it.each(['IN_PROGRESS', 'BLOCKED', 'WAITING_ON_CLIENT'])('is In process for %s', (status) => {
    expect(dot(status)).toBe('IN_PROCESS')
  })

  it.each(['DONE', 'SKIPPED'])('is Completed for %s', (status) => {
    expect(dot(status)).toBe('COMPLETED')
  })

  /** Late beats moving: the row has one colour and that is the urgent fact. */
  it('is Overdue for anything unsettled the server calls overdue', () => {
    expect(dot('PENDING', true)).toBe('OVERDUE')
    expect(dot('IN_PROGRESS', true)).toBe('OVERDUE')
    expect(dot('BLOCKED', true)).toBe('OVERDUE')
  })

  /**
   * Work finished late is finished. A row that went red for ever the day after
   * its due date would be the page arguing with its own tick.
   */
  it.each(['DONE', 'SKIPPED'])('never reddens %s, however late it was', (status) => {
    expect(dot(status, true)).toBe('COMPLETED')
  })

  /** A status added server-side is grey rather than a crash or a blank cell. */
  it('is Pending for a status it does not know', () => {
    expect(dot('SOMETHING_NEW')).toBe('PENDING')
  })
})

describe('myTaskDotLabel', () => {
  const label = (status: string, isOverdue = false) =>
    myTaskDotLabel({ status, isOverdue } as Pick<ObMyTask, 'status' | 'isOverdue'>)

  /** Never colour alone — the dot's name is its hue in words. */
  it('names the four states', () => {
    expect(label('PENDING')).toBe('Pending')
    expect(label('IN_PROGRESS')).toBe('In process')
    expect(label('PENDING', true)).toBe('Overdue')
    expect(label('DONE')).toBe('Completed')
  })

  /** Four colours in the column, but no status lost to them. */
  it('keeps the exact status where the colour does not say it', () => {
    expect(label('BLOCKED')).toBe('In process — blocked')
    expect(label('WAITING_ON_CLIENT')).toBe('In process — waiting on client')
    expect(label('SKIPPED')).toBe('Completed — waived')
    expect(label('BLOCKED', true)).toBe('Overdue — blocked')
  })
})

describe('formatDueDate', () => {
  it('formats a due date', () => {
    expect(formatDueDate('2026-09-16T13:00:00Z')).toMatch(/Sep/)
  })

  /** Null until the task activates — an em dash, never "Invalid Date". */
  it('prints a dash for a task that has not activated', () => {
    expect(formatDueDate(null)).toBe('—')
    expect(formatDueDate(undefined)).toBe('—')
    expect(formatDueDate('not a date')).toBe('—')
  })
})

const ROW: ObMyTask = {
  rowsOut: 0,
  rowsReturned: 0,
  rowsApproved: 0,
  taskId: 900,
  taskName: 'Week off',
  status: 'PENDING',
  dueAt: '2026-09-16T13:00:00Z',
  isOverdue: false,
  projectId: 7,
  projectName: 'DAV Proj',
  obClientId: 3,
  obClientName: 'DAV School',
  obClientCode: 'DAV-101',
  journeyId: 500,
  serviceName: 'Student Attendance',
  stepKey: 1,
  stepName: 'Configuration',
  stepSequence: 1,
}

describe('projectLabel', () => {
  it('joins the client to the project it runs', () => {
    expect(projectLabel(ROW)).toBe('DAV School — DAV Proj')
  })

  /**
   * Provisioning names a project after its client, so the plain join would
   * print the school twice on exactly the rows it created.
   */
  it('drops a client prefix the project name already carries', () => {
    expect(
      projectLabel({
        obClientName: 'Delhi Public School',
        projectName: 'Delhi Public School — EDUNEXT-ERP',
      }),
    ).toBe('Delhi Public School — EDUNEXT-ERP')
    expect(
      projectLabel({ obClientName: 'Delhi Public School', projectName: 'Delhi Public School: ERP' }),
    ).toBe('Delhi Public School — ERP')
    // Provisioning's casing is not guaranteed to match the client master's.
    expect(
      projectLabel({ obClientName: 'DAV School', projectName: 'dav school - Rollout' }),
    ).toBe('DAV School — Rollout')
  })

  /** A project named for its client and nothing else is the client, once. */
  it('prints the client once when the project adds nothing', () => {
    expect(projectLabel({ obClientName: 'DAV School', projectName: 'DAV School' })).toBe(
      'DAV School',
    )
  })

  /** Half a label beats a stray dash on a row with a field missing. */
  it('never leaves a dangling separator', () => {
    expect(projectLabel({ obClientName: '', projectName: 'DAV Proj' })).toBe('DAV Proj')
    expect(projectLabel({ obClientName: 'DAV School', projectName: '' })).toBe('DAV School')
  })

  /** A project whose name merely starts with a similar word keeps all of it. */
  it('does not trim a prefix that is not the client', () => {
    expect(
      projectLabel({ obClientName: 'Vasundhara School', projectName: 'Vasundhara Project' }),
    ).toBe('Vasundhara School — Vasundhara Project')
  })
})

describe('serviceStepLabel', () => {
  it('joins the module service to the step within it', () => {
    expect(serviceStepLabel(ROW)).toBe('Student Attendance — Configuration')
    expect(serviceStepLabel({ serviceName: 'SIS', stepName: 'Web/App Reflection' })).toBe(
      'SIS — Web/App Reflection',
    )
  })

  it('never leaves a dangling separator', () => {
    expect(serviceStepLabel({ serviceName: 'SIS', stepName: '' })).toBe('SIS')
    expect(serviceStepLabel({ serviceName: '', stepName: 'Configuration' })).toBe('Configuration')
  })
})

function step(over: Partial<ObJourneyStepView> = {}): ObJourneyStepView {
  return {
    id: 900,
    journeyId: 500,
    sequence: 2,
    name: 'Week off',
    status: 'IN_PROGRESS',
    tatDays: 3,
    ownerUserId: 41,
    ownerIsInherited: true,
    backupOwnerUserId: null,
    requiresSignoff: true,
    dueAt: '2026-09-16T13:00:00Z',
    tatUsedPercent: 42,
    stageKey: 1,
    stageName: 'Configuration',
    items: [],
    docs: [],
    ...over,
  } as ObJourneyStepView
}

describe('focusedTask', () => {
  it('builds the panel’s task from the journey read', () => {
    const task = focusedTask(ROW, [step({ id: 899 }), step()])

    expect(task).not.toBeNull()
    expect(task?.id).toBe(900)
    expect(task?.name).toBe('Week off')
    // The live state comes from the journey, not from the row: the row was
    // fetched for the labels and may be a moment older.
    expect(task?.status).toBe('IN_PROGRESS')
    expect(task?.tatDays).toBe(3)
    expect(task?.tatUsedPercent).toBe(42)
    expect(task?.ownerIsInherited).toBe(true)
  })

  /**
   * The row is what the grid printed and what the reader clicked. If the two
   * ever disagreed, a focused page whose step name was not the one on the row
   * would be the more confusing failure.
   */
  it('takes the service and step names from the row', () => {
    const task = focusedTask(ROW, [step({ stageName: 'Something else', stageKey: 99 })])

    expect(task?.serviceName).toBe('Student Attendance')
    expect(task?.stageName).toBe('Configuration')
    expect(task?.stageKey).toBe(1)
  })

  it('answers null when the journey does not carry the task', () => {
    expect(focusedTask(ROW, [step({ id: 12 })])).toBeNull()
    expect(focusedTask(ROW, [])).toBeNull()
    expect(focusedTask(ROW, undefined)).toBeNull()
  })

  /** Absent optional fields become the panel's own empty states, never undefined. */
  it('fills in the fields the read leaves out', () => {
    const task = focusedTask(
      ROW,
      [
        {
          id: 900,
          journeyId: 500,
          sequence: 1,
          name: 'Week off',
          status: 'PENDING',
        } as ObJourneyStepView,
      ],
    )

    expect(task?.tatDays).toBe(0)
    expect(task?.requiresSignoff).toBe(false)
    expect(task?.ownerIsInherited).toBe(false)
    expect(task?.tatUsedPercent).toBeNull()
    expect(task?.items).toEqual([])
    expect(task?.docs).toEqual([])
  })
})
