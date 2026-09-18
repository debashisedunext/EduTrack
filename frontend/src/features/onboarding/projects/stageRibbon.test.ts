import { describe, expect, it } from 'vitest'
import type { ObProjectStage } from '@/api/generated/model/obProjectStage'

import { orderStages } from './stageRibbon'

/**
 * Ordering, and only ordering — see `stageRibbon.ts` for where the rest of this
 * file's vocabulary went. The tie-break is the case worth keeping: the seeded
 * corpus can carry several Ungrouped buckets, all on sequence 9999.
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
