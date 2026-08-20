import { describe, expect, it } from 'vitest'
import { matchesSegmentFilter } from './segmentFilter'

describe('matchesSegmentFilter', () => {
  const filter = { stageCode: 'DEVELOPMENT', iterationNo: 2, cycleNo: 1, displayName: 'Development' }

  it('matches a row on the same stage, iteration and cycle', () => {
    expect(matchesSegmentFilter({ stageCode: 'DEVELOPMENT', iterationNo: 2, cycleNo: 1 }, filter)).toBe(true)
  })

  it('rejects a different stage', () => {
    expect(matchesSegmentFilter({ stageCode: 'QA', iterationNo: 2, cycleNo: 1 }, filter)).toBe(false)
  })

  it('rejects a different iteration', () => {
    expect(matchesSegmentFilter({ stageCode: 'DEVELOPMENT', iterationNo: 1, cycleNo: 1 }, filter)).toBe(false)
  })

  // The trap `stream-tickets` names: the same stage and iteration can recur in
  // a later cycle after a reopen, and must not be folded into an earlier one.
  it('rejects the same stage and iteration in a different cycle', () => {
    expect(matchesSegmentFilter({ stageCode: 'DEVELOPMENT', iterationNo: 2, cycleNo: 2 }, filter)).toBe(false)
  })

  it('treats a missing iterationNo as 1 on both sides', () => {
    const firstIteration = { stageCode: 'INTAKE', cycleNo: 1, displayName: 'Intake' }
    expect(matchesSegmentFilter({ stageCode: 'INTAKE', cycleNo: 1 }, firstIteration)).toBe(true)
    expect(matchesSegmentFilter({ stageCode: 'INTAKE', iterationNo: 1, cycleNo: 1 }, firstIteration)).toBe(true)
  })

  it('ignores cycle entirely when the filter carries none', () => {
    const noCycle = { stageCode: 'INTAKE', iterationNo: 1, displayName: 'Intake' }
    expect(matchesSegmentFilter({ stageCode: 'INTAKE', iterationNo: 1, cycleNo: 5 }, noCycle)).toBe(true)
  })
})
