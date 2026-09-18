import { describe, expect, it } from 'vitest'

import { taskDotLabel, taskDotState } from './taskStatus'
import type { ProjectTask } from './useProjectTasks'

/**
 * The four colours the task strip is scanned by, over the six statuses the
 * server keeps — and the one of them that is read off the clock.
 *
 * <p>Dates are the distant past and the distant future rather than offsets
 * from `Date.now()`, so a run in 2027 asserts the same thing a run today does.
 */

const PAST = '2020-01-01T09:00:00Z'
const FUTURE = '2999-01-01T09:00:00Z'

function task(over: Partial<ProjectTask> = {}): ProjectTask {
  return {
    id: 900,
    journeyId: 500,
    serviceName: 'SIS',
    sequence: 1,
    name: 'Student Dataport',
    status: 'PENDING',
    ownerUserId: 41,
    ownerIsInherited: false,
    backupOwnerUserId: null,
    tatDays: 1,
    requiresSignoff: false,
    dueAt: FUTURE,
    tatUsedPercent: null,
    stageKey: 7,
    stageName: 'Data Migration',
    items: [],
    docs: [],
    ...over,
  }
}

function item(answer: boolean | null) {
  return {
    id: 1,
    stepId: 900,
    sequence: 1,
    label: 'Data sanitisation',
    isMandatory: true,
    isDone: answer === true,
    answer,
    remark: null,
  }
}

describe('taskDotState', () => {
  it('is Pending for a task nobody has touched', () => {
    expect(taskDotState(task())).toBe('PENDING')
  })

  it('is In process once the task is moving', () => {
    expect(taskDotState(task({ status: 'IN_PROGRESS' }))).toBe('IN_PROCESS')
  })

  /**
   * Both are states a task reaches by being worked on — the same reading the
   * Module strip's partial bucket takes, so the dots and the strip above them
   * cannot disagree about the same row.
   */
  it.each(['BLOCKED', 'WAITING_ON_CLIENT'] as const)('is In process for %s', (status) => {
    expect(taskDotState(task({ status }))).toBe('IN_PROCESS')
  })

  /** A check list somebody has started is work in progress, whatever the status says. */
  it('is In process for an untouched status with an answered check list', () => {
    expect(taskDotState(task({ items: [item(false)] }))).toBe('IN_PROCESS')
  })

  it.each(['DONE', 'SKIPPED'] as const)('is Completed for %s', (status) => {
    expect(taskDotState(task({ status }))).toBe('COMPLETED')
  })

  describe('overdue', () => {
    it('is Overdue once the due date has passed and the task has not settled', () => {
      expect(taskDotState(task({ dueAt: PAST }))).toBe('OVERDUE')
      expect(taskDotState(task({ status: 'IN_PROGRESS', dueAt: PAST }))).toBe('OVERDUE')
      expect(taskDotState(task({ status: 'BLOCKED', dueAt: PAST }))).toBe('OVERDUE')
    })

    /**
     * Work finished late is finished. A row that went red for ever the day
     * after its due date would be the page arguing with its own tick.
     */
    it.each(['DONE', 'SKIPPED'] as const)('never reddens %s, however late it was', (status) => {
      expect(taskDotState(task({ status, dueAt: PAST }))).toBe('COMPLETED')
    })

    it('is not overdue when no due date was pinned at all', () => {
      expect(taskDotState(task({ dueAt: null }))).toBe('PENDING')
      expect(taskDotState(task({ status: 'IN_PROGRESS', dueAt: undefined }))).toBe('IN_PROCESS')
    })
  })
})

describe('taskDotLabel', () => {
  /** Never colour alone — the dot's name is its hue in words. */
  it('names the four states', () => {
    expect(taskDotLabel(task())).toBe('Pending')
    expect(taskDotLabel(task({ status: 'IN_PROGRESS' }))).toBe('In process')
    expect(taskDotLabel(task({ dueAt: PAST }))).toBe('Overdue')
    expect(taskDotLabel(task({ status: 'DONE' }))).toBe('Completed')
  })

  /** Four colours on the strip, but no status lost to them. */
  it('keeps the exact status where the colour does not say it', () => {
    expect(taskDotLabel(task({ status: 'BLOCKED' }))).toBe('In process — blocked')
    expect(taskDotLabel(task({ status: 'WAITING_ON_CLIENT' }))).toBe(
      'In process — waiting on client',
    )
    expect(taskDotLabel(task({ status: 'SKIPPED' }))).toBe('Completed — waived')
    expect(taskDotLabel(task({ status: 'BLOCKED', dueAt: PAST }))).toBe('Overdue — blocked')
  })
})
