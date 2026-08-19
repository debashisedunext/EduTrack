import { describe, expect, it } from 'vitest'

import type { JourneyRow } from '@/api/generated/model/journeyRow'
import { cycleElapsedMins, rollupByResource } from './journeyRollup'

function hop(over: Partial<JourneyRow> = {}): JourneyRow {
  return {
    iterationNo: 1,
    cycleNo: 1,
    stageCode: 'DEV',
    resource: { id: 8, displayName: 'Ravi Kumar' },
    role: 'DEVELOPER',
    enteredAt: '2026-08-01T09:00:00Z',
    exitedAt: '2026-08-01T17:00:00Z',
    durationMins: 480,
    effortHrs: 4,
    idleMins: 240,
    ...over,
  }
}

describe('rollupByResource', () => {
  it('counts a person’s distinct stages and iterations, not their hops', () => {
    // Ravi held DEV twice — iteration 1 and, after a QA reject, iteration 2.
    // §4A.4 calls that "1 stage, 2 iterations", not "2 stages".
    const people = rollupByResource([
      hop({ stageCode: 'DEV', iterationNo: 1 }),
      hop({ stageCode: 'DEV', iterationNo: 2 }),
    ])
    expect(people).toHaveLength(1)
    expect(people[0]).toMatchObject({ stages: 1, iterations: 2, elapsedMins: 960, effortHrs: 8 })
  })

  it('adds up elapsed and effort across a person’s hops', () => {
    const people = rollupByResource([
      hop({ stageCode: 'DEV', durationMins: 480, effortHrs: 4 }),
      hop({ stageCode: 'VERIFY', durationMins: 120, effortHrs: 1.5 }),
    ])
    expect(people[0]).toMatchObject({ stages: 2, elapsedMins: 600, effortHrs: 5.5 })
  })

  it('separates people and orders by effort, heaviest first', () => {
    const anil = { id: 14, displayName: 'Anil Sharma' }
    const people = rollupByResource([
      hop({ effortHrs: 2 }),
      hop({ resource: anil, role: 'QA', stageCode: 'QA', effortHrs: 9 }),
    ])
    expect(people.map((p) => p.displayName)).toEqual(['Anil Sharma', 'Ravi Kumar'])
  })

  it('leaves an unassigned hop out of the per-person band', () => {
    // §4A.2's project-level queue belongs to nobody. Inventing an "Unassigned"
    // pseudo-person would put a queue in the same list as the people.
    const people = rollupByResource([hop({ resource: undefined }), hop()])
    expect(people).toHaveLength(1)
    expect(people[0].displayName).toBe('Ravi Kumar')
  })

  it('treats an open hop as contributing no elapsed time', () => {
    const people = rollupByResource([hop({ durationMins: null, effortHrs: 2 })])
    expect(people[0]).toMatchObject({ elapsedMins: 0, effortHrs: 2 })
  })
})

describe('cycleElapsedMins', () => {
  it('sums the hops rather than measuring first entry to last exit', () => {
    // A ticket can sit outside any stage between a close and a reopen, and
    // counting that gap would make the total a different measure from the
    // column above it.
    expect(cycleElapsedMins([hop({ durationMins: 480 }), hop({ durationMins: 120 })])).toBe(600)
  })

  it('ignores the open hop', () => {
    expect(cycleElapsedMins([hop({ durationMins: 480 }), hop({ durationMins: null })])).toBe(480)
  })
})
