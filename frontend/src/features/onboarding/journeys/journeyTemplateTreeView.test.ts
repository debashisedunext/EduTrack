import { describe, expect, it } from 'vitest'

import type { ObJourneyTemplateStep } from '@/api/generated/model/obJourneyTemplateStep'

import {
  buildTaskFilter,
  countsLabel,
  levelOf,
  presetFor,
  stageKey,
  stageSpan,
  stepAndTaskCounts,
  taskKey,
  TREE_LEVELS,
} from './journeyTemplateTreeView'

/**
 * The view state behind the five-level tree: which rows are open, which level
 * that adds up to, and what a search box narrows it to.
 *
 * <p>The case this file exists for is the one the counts get wrong if they
 * read tree depth instead of the dependency — a task held by something in
 * another stage draws at its stage's left margin and is still a Task.
 */
function task(
  id: number,
  over: Partial<ObJourneyTemplateStep> = {},
): ObJourneyTemplateStep {
  return {
    id,
    sequence: id,
    name: `Task ${id}`,
    templateStageId: 1,
    description: null,
    tatDays: 1,
    ownerUserId: null,
    requiresSignoff: false,
    dependsOnStepId: null,
    items: [],
    docs: [],
    ...over,
  }
}

describe('stepAndTaskCounts', () => {
  it('calls an unheld row a Step and a held one a Task', () => {
    const counts = stepAndTaskCounts([task(1), task(2, { dependsOnStepId: 1 }), task(3)])
    expect(counts).toEqual({ steps: 2, tasks: 1 })
  })

  it('still calls a cross-stage dependent a Task, though it draws at the margin', () => {
    // Task 9 lives in stage 2 and waits on something in stage 1, so its own
    // stage's tree makes it a root. Counting depth would call it a Step and
    // claim the journey starts in two places at once.
    const inStageTwo = [task(9, { templateStageId: 2, dependsOnStepId: 1 })]
    expect(stepAndTaskCounts(inStageTwo)).toEqual({ steps: 0, tasks: 1 })
  })
})

describe('countsLabel', () => {
  it('names both kinds when a stage holds both', () => {
    expect(countsLabel([task(1), task(2, { dependsOnStepId: 1 })])).toBe('1 step · 1 task')
  })

  it('names only the kind that is there', () => {
    expect(countsLabel([task(1), task(2)])).toBe('2 steps')
    expect(countsLabel([task(2, { dependsOnStepId: 1 })])).toBe('1 task')
  })

  it('says an empty stage is empty rather than showing two zeroes', () => {
    expect(countsLabel([])).toBe('No tasks yet')
  })
})

describe('presetFor and levelOf', () => {
  const keys = { stageIds: [10, 11], taskIds: [1, 2, 3] }

  it('round-trips every level', () => {
    for (const level of ['stage', 'task', 'subtask'] as const) {
      expect(levelOf(presetFor(level, keys), keys)).toBe(level)
    }
  })

  it('offers three segments, and Step is not one of them', () => {
    // The strip is Stage / Task / Checklist. "Step" was a fourth segment
    // whose name collided with the Step badge on the cards below it.
    expect(TREE_LEVELS.map((l) => l.label)).toEqual(['Stage', 'Task', 'Checklist'])
  })

  it('closes the stages themselves at the stage level', () => {
    const state = presetFor('stage', keys)
    expect([...state.collapsed].sort()).toEqual([stageKey(10), stageKey(11)].sort())
    expect(state.showSubtasks).toBe(false)
  })

  it('reports no level for the every-task-closed state the Step segment used to name', () => {
    // Still reachable by collapsing each task by hand; it just is not a
    // preset any more, so nothing in the strip claims to be showing it.
    expect(
      levelOf(
        { collapsed: new Set([taskKey(1), taskKey(2), taskKey(3)]), showSubtasks: false },
        keys,
      ),
    ).toBeNull()
  })

  it('reports no level once a single row is toggled by hand', () => {
    // Which is the honest answer: the tree is no longer at any one level, so
    // no segment should be highlighted claiming otherwise.
    const state = presetFor('subtask', keys)
    const nudged = { ...state, collapsed: new Set([taskKey(2)]) }
    expect(levelOf(nudged, keys)).toBeNull()
  })

  it('does not confuse "everything open, sub-tasks hidden" with the sub-task level', () => {
    expect(levelOf({ collapsed: new Set(), showSubtasks: false }, keys)).toBe('task')
    expect(levelOf({ collapsed: new Set(), showSubtasks: true }, keys)).toBe('subtask')
  })
})

describe('buildTaskFilter', () => {
  const steps = [
    task(1, { name: 'Kick-off call' }),
    task(2, {
      name: 'Device inventory sign-off',
      dependsOnStepId: 1,
      items: [{ id: 21, sequence: 1, label: 'Serial numbers reconciled', mandatory: true }],
    }),
    task(3, {
      name: 'Network readiness',
      dependsOnStepId: 2,
      docs: [{ id: 31, sequence: 1, label: 'Firewall policy export', required: true }],
    }),
    task(4, { name: 'Tenant provisioning' }),
  ]

  it('is null for a blank box, which is the whole tree rather than a match-all', () => {
    expect(buildTaskFilter(steps, '   ')).toBeNull()
  })

  it('keeps every ancestor of a hit, so nothing floats out of its stage', () => {
    const filter = buildTaskFilter(steps, 'network')!
    expect([...filter.visibleTaskIds].sort()).toEqual([1, 2, 3])
    expect([...filter.matchedTaskIds]).toEqual([3])
  })

  it('matches a sub-task and pulls its task in with it', () => {
    const filter = buildTaskFilter(steps, 'serial numbers')!
    expect([...filter.visibleTaskIds].sort()).toEqual([1, 2])
    // The task itself did not match, so only the item that did opens.
    expect(filter.matchedTaskIds.size).toBe(0)
    expect([...filter.matchedItemIds]).toEqual([21])
  })

  it('matches a required document by its label', () => {
    const filter = buildTaskFilter(steps, 'firewall')!
    expect([...filter.matchedDocIds]).toEqual([31])
    expect(filter.visibleTaskIds.has(3)).toBe(true)
  })

  it('ignores case and searches the description too', () => {
    const described = [task(7, { name: 'Handover', description: 'Runbook walkthrough' })]
    expect(buildTaskFilter(described, 'RUNBOOK')!.matchedTaskIds.has(7)).toBe(true)
  })

  it('drops a branch nothing in it matched', () => {
    const filter = buildTaskFilter(steps, 'kick-off')!
    expect(filter.visibleTaskIds.has(4)).toBe(false)
  })
})

describe('stageSpan', () => {
  const schedule = new Map([
    [1, { startDay: 1, endDay: 3 }],
    [2, { startDay: 4, endDay: 5 }],
    [3, { startDay: 1, endDay: 1 }],
  ])

  it('runs from the earliest start to the latest end inside the stage', () => {
    expect(stageSpan([task(1), task(2), task(3)], schedule)).toEqual({ startDay: 1, endDay: 5 })
  })

  it('is null for a stage holding nothing', () => {
    expect(stageSpan([], schedule)).toBeNull()
  })

  it('ignores a task the template-wide tree has not placed', () => {
    expect(stageSpan([task(1), task(99)], schedule)).toEqual({ startDay: 1, endDay: 3 })
  })
})
