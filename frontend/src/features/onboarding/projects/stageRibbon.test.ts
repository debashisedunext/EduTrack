import { describe, expect, it } from 'vitest'
import type { ObProjectStage } from '@/api/generated/model/obProjectStage'

import {
  defaultStageKey,
  orderStages,
  stageCountLabel,
  stageFraction,
  stageSettled,
  stageState,
} from './stageRibbon'

/**
 * The ribbon's vocabulary. Three of these turn on a stage with **no tasks**,
 * which is the case the seeded corpus actually carries — template 24 publishes
 * all seven implementation stages and schedules tasks into two of them.
 */

function stage(over: Partial<ObProjectStage> = {}): ObProjectStage {
  return {
    stageKey: 1,
    name: 'Configuration',
    sequence: 1,
    taskCount: 3,
    tasksOutstanding: 1,
    isComplete: false,
    isCurrent: false,
    ...over,
  }
}

describe('stageState', () => {
  it('reads a stage with no tasks as empty, never as pending', () => {
    // Pending would have a reader waiting on work nobody scheduled.
    expect(stageState(stage({ taskCount: 0, tasksOutstanding: 0 }))).toBe('empty')
  })

  it('prefers empty over complete for a stage with nothing in it', () => {
    // The server sets isComplete on a zero-task stage — vacuously true, since
    // nothing is outstanding. Reading that as "complete" would tick off a
    // stage that was never configured.
    expect(stageState(stage({ taskCount: 0, tasksOutstanding: 0, isComplete: true }))).toBe('empty')
  })

  it('prefers complete over current', () => {
    expect(stageState(stage({ isComplete: true, isCurrent: true }))).toBe('complete')
  })

  it('is current only while something is actually running', () => {
    expect(stageState(stage({ isCurrent: true }))).toBe('current')
    expect(stageState(stage({ isCurrent: false }))).toBe('pending')
  })
})

describe('stageSettled and stageFraction', () => {
  it('counts settled as the complement of outstanding', () => {
    expect(stageSettled(stage({ taskCount: 5, tasksOutstanding: 2 }))).toBe(3)
  })

  it('fills nothing for an empty stage rather than dividing to 100%', () => {
    expect(stageFraction(stage({ taskCount: 0, tasksOutstanding: 0 }))).toBe(0)
  })

  it('fills completely when nothing is outstanding', () => {
    expect(stageFraction(stage({ taskCount: 4, tasksOutstanding: 0 }))).toBe(1)
  })

  it('never reports past full if outstanding somehow exceeds the count', () => {
    expect(stageSettled(stage({ taskCount: 2, tasksOutstanding: 5 }))).toBe(0)
    expect(stageFraction(stage({ taskCount: 2, tasksOutstanding: 5 }))).toBe(0)
  })
})

describe('stageCountLabel', () => {
  it('names an empty stage rather than printing 0/0', () => {
    expect(stageCountLabel(stage({ taskCount: 0, tasksOutstanding: 0 }))).toBe('No tasks')
  })

  it('prints settled over total', () => {
    expect(stageCountLabel(stage({ taskCount: 3, tasksOutstanding: 1 }))).toBe('2/3 tasks')
  })
})

describe('orderStages', () => {
  it('sorts by the master sequence', () => {
    const out = orderStages([
      stage({ stageKey: 2, name: 'Data Migration', sequence: 2 }),
      stage({ stageKey: 1, name: 'Configuration', sequence: 1 }),
    ])
    expect(out.map((s) => s.name)).toEqual(['Configuration', 'Data Migration'])
  })

  it('breaks ties on key, so several Ungrouped buckets hold a stable order', () => {
    const out = orderStages([
      stage({ stageKey: -9, name: 'Ungrouped', sequence: 9999 }),
      stage({ stageKey: -4, name: 'Ungrouped', sequence: 9999 }),
    ])
    expect(out.map((s) => s.stageKey)).toEqual([-9, -4])
  })

  it('does not mutate its input', () => {
    const input = [stage({ stageKey: 2, sequence: 2 }), stage({ stageKey: 1, sequence: 1 })]
    orderStages(input)
    expect(input.map((s) => s.stageKey)).toEqual([2, 1])
  })
})

describe('defaultStageKey', () => {
  it('opens on the running stage', () => {
    const key = defaultStageKey([
      stage({ stageKey: 1, sequence: 1, isComplete: true, tasksOutstanding: 0 }),
      stage({ stageKey: 2, sequence: 2, isCurrent: true }),
      stage({ stageKey: 3, sequence: 3 }),
    ])
    expect(key).toBe(2)
  })

  it('falls back to the first stage with outstanding work when nothing runs', () => {
    // A locked gate is the ordinary case here: every task pending, no current.
    const key = defaultStageKey([
      stage({ stageKey: 1, sequence: 1, taskCount: 2, tasksOutstanding: 0, isComplete: true }),
      stage({ stageKey: 2, sequence: 2, taskCount: 2, tasksOutstanding: 2 }),
    ])
    expect(key).toBe(2)
  })

  it('skips empty stages when choosing a fallback', () => {
    const key = defaultStageKey([
      stage({ stageKey: 1, sequence: 1, taskCount: 0, tasksOutstanding: 0 }),
      stage({ stageKey: 2, sequence: 2, taskCount: 1, tasksOutstanding: 1 }),
    ])
    expect(key).toBe(2)
  })

  it('opens a finished project on its first real stage rather than on nothing', () => {
    const key = defaultStageKey([
      stage({ stageKey: 1, sequence: 1, taskCount: 2, tasksOutstanding: 0, isComplete: true }),
      stage({ stageKey: 2, sequence: 2, taskCount: 0, tasksOutstanding: 0 }),
    ])
    expect(key).toBe(1)
  })

  it('is null for a project with no stages at all', () => {
    expect(defaultStageKey([])).toBeNull()
  })
})
