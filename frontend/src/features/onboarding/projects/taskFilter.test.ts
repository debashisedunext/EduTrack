import { describe, expect, it } from 'vitest'

import type { ObProjectStage } from '@/api/generated/model/obProjectStage'

import type { TreeService, TreeStage } from './projectTree'
import { firstServiceWithWork, hiddenStepCount, stepViews } from './taskFilter'
import type { ProjectTask } from './useProjectTasks'

/**
 * The two positions of the project page's switch, over the one distinction
 * that decides them: settled work against everything else.
 */

function stage(stageKey: number, name: string): ObProjectStage {
  return {
    stageKey,
    name,
    sequence: stageKey,
    taskCount: 1,
    tasksOutstanding: 1,
    isComplete: false,
    isCurrent: false,
  }
}

function task(id: number, status: ProjectTask['status']): ProjectTask {
  return {
    id,
    journeyId: 500,
    serviceName: 'SIS',
    sequence: id,
    name: `Task ${id}`,
    status,
    ownerUserId: 41,
    ownerIsInherited: false,
    backupOwnerUserId: null,
    tatDays: 1,
    requiresSignoff: false,
    dueAt: null,
    tatUsedPercent: null,
    stageKey: 1,
    stageName: 'Configuration',
    items: [],
    docs: [],
  }
}

function step(stageKey: number, name: string, tasks: ProjectTask[]): TreeStage {
  return {
    stage: stage(stageKey, name),
    tasks,
    settled: tasks.filter((t) => t.status === 'DONE' || t.status === 'SKIPPED').length,
    hasMine: true,
  }
}

function service(journeyId: number, stages: TreeStage[]): TreeService {
  const tasks = stages.flatMap((s) => s.tasks)
  return {
    service: {
      journeyId,
      templateId: 9,
      serviceName: `Service ${journeyId}`,
      gateStatus: 'OPEN',
      isComplete: false,
      stages: stages.map((s) => s.stage),
    },
    stages,
    tasks,
    allTasks: tasks,
    taskCount: tasks.length,
    settled: stages.reduce((n, s) => n + s.settled, 0),
    stagesComplete: stages.filter((s) => s.tasks.length > 0 && s.settled === s.tasks.length).length,
    stageCount: stages.length,
    hiddenStageCount: 0,
    hasMine: true,
    totalTatDays: tasks.length,
  }
}

const FINISHED = step(1, 'Configuration', [task(1, 'DONE'), task(2, 'SKIPPED')])
const MIXED = step(2, 'Data Migration', [task(3, 'DONE'), task(4, 'IN_PROGRESS')])
const UNTOUCHED = step(3, 'Reports', [task(5, 'PENDING')])
const STAGES = [FINISHED, MIXED, UNTOUCHED]

describe('stepViews under Pending', () => {
  it('drops a Step whose work is all finished rather than drawing it empty', () => {
    const views = stepViews(STAGES, 'PENDING')

    expect(views.map((v) => v.step.stage.name)).toEqual(['Data Migration', 'Reports'])
  })

  /**
   * The switch's Pending is *not* `ObJourneyStepStatus.PENDING`: a task
   * somebody started, or is blocked on, or is waiting on the client for, is
   * precisely the work the reader came to find.
   */
  it('keeps every task that is neither DONE nor SKIPPED', () => {
    const started = step(4, 'Training', [
      task(6, 'PENDING'),
      task(7, 'IN_PROGRESS'),
      task(8, 'BLOCKED'),
      task(9, 'WAITING_ON_CLIENT'),
      task(10, 'DONE'),
      task(11, 'SKIPPED'),
    ])

    expect(stepViews([started], 'PENDING')[0].tasks.map((t) => t.id)).toEqual([6, 7, 8, 9])
  })

  /** Nothing is removed silently — the Step says what it is holding back. */
  it('counts the finished tasks it left inside a Step it kept', () => {
    const [migration] = stepViews([MIXED], 'PENDING')

    expect(migration.tasks.map((t) => t.id)).toEqual([4])
    expect(migration.hiddenCount).toBe(1)
    // The Step itself is untouched, so its header keeps counting all of it.
    expect(migration.step.tasks).toHaveLength(2)
  })

  it('counts the Steps it left out altogether', () => {
    expect(hiddenStepCount(STAGES, 'PENDING')).toBe(1)
    expect(hiddenStepCount(STAGES, 'ALL')).toBe(0)
  })
})

describe('stepViews under Show all', () => {
  it('draws every Step and every task, hiding nothing', () => {
    const views = stepViews(STAGES, 'ALL')

    expect(views.map((v) => v.step.stage.name)).toEqual([
      'Configuration',
      'Data Migration',
      'Reports',
    ])
    expect(views.every((v) => v.hiddenCount === 0)).toBe(true)
    expect(views.flatMap((v) => v.tasks)).toHaveLength(5)
  })
})

describe('firstServiceWithWork', () => {
  it('skips a finished module for the one that still has something in it', () => {
    const tree = [service(500, [FINISHED]), service(501, [UNTOUCHED])]

    expect(firstServiceWithWork(tree, 'PENDING')).toBe(501)
    // Asked for everything, the first module is the one that opens.
    expect(firstServiceWithWork(tree, 'ALL')).toBe(500)
  })

  /** A project whose work is done opens nothing — not a fault, and Show all has it back. */
  it('is null when no module has outstanding work', () => {
    expect(firstServiceWithWork([service(500, [FINISHED])], 'PENDING')).toBeNull()
  })

  it('is null for a project boarded through no module at all', () => {
    expect(firstServiceWithWork([], 'PENDING')).toBeNull()
  })
})
