import { describe, expect, it } from 'vitest'

import type { JourneyStep } from './types'
import { dependencyBadge, statusEmoji, stepAriaLabel, tatBarLevel, treatmentFor } from './stepState'

function step(over: Partial<JourneyStep> = {}): JourneyStep {
  return {
    id: 's1',
    seqNo: 3,
    name: 'Data migration',
    status: 'CURRENT',
    owner: { displayName: 'Priya Nair' },
    ownerRole: 'STEP_OWNER',
    tatDays: 8,
    tatPercent: 40,
    dependsOnSeqNo: 2,
    ...over,
  }
}

describe('treatmentFor · the five states', () => {
  it('gives every state its own label and card treatment', () => {
    expect(treatmentFor(step({ status: 'PENDING', tatPercent: null })).label).toBe('Pending')
    expect(treatmentFor(step({ status: 'CURRENT' })).label).toBe('In progress')
    expect(treatmentFor(step({ status: 'DONE', tatPercent: null })).label).toBe('Done')
    expect(treatmentFor(step({ status: 'WAITING' })).label).toBe('Waiting on client')
    expect(treatmentFor(step({ status: 'BLOCKED' })).label).toBe('Blocked')
  })

  it('overlays breach on a running step past its TAT, not a seventh state', () => {
    const onTime = treatmentFor(step({ status: 'CURRENT', tatPercent: 60 }))
    const breached = treatmentFor(step({ status: 'CURRENT', tatPercent: 100 }))
    expect(onTime.isBreached).toBe(false)
    expect(breached.isBreached).toBe(true)
    expect(breached.label).toBe('Breached')
  })

  it('overlays breach on a blocked step too, without losing the blocked treatment', () => {
    const breached = treatmentFor(step({ status: 'BLOCKED', tatPercent: 140 }))
    expect(breached.isBreached).toBe(true)
    expect(breached.label).toContain('Blocked')
  })

  it('never overlays breach on PENDING, DONE or WAITING', () => {
    expect(treatmentFor(step({ status: 'PENDING', tatPercent: 150 })).isBreached).toBe(false)
    expect(treatmentFor(step({ status: 'DONE', tatPercent: 150 })).isBreached).toBe(false)
    expect(treatmentFor(step({ status: 'WAITING', tatPercent: 150 })).isBreached).toBe(false)
  })

  it('falls back to PENDING for a status this client cannot read', () => {
    expect(treatmentFor(step({ status: 'SOMETHING_NEW' as JourneyStep['status'] })).label).toBe('Pending')
  })
})

describe('tatBarLevel', () => {
  it('reads ok below the amber threshold, amber at it, red at or past 100', () => {
    expect(tatBarLevel(50)).toBe('ok')
    expect(tatBarLevel(75)).toBe('amber')
    expect(tatBarLevel(99)).toBe('amber')
    expect(tatBarLevel(100)).toBe('red')
    expect(tatBarLevel(140)).toBe('red')
  })

  it('respects a custom amber threshold — OB-11 will make this admin-editable', () => {
    expect(tatBarLevel(60, 50)).toBe('amber')
  })

  it('reads ok for a step with no percent yet', () => {
    expect(tatBarLevel(null)).toBe('ok')
  })
})

describe('statusEmoji · Onboarding-Module-Plan.md §9', () => {
  it('draws nothing for a PENDING step', () => {
    expect(statusEmoji(step({ status: 'PENDING', tatPercent: null }))).toBeNull()
  })

  it('draws the clap for a running step on time', () => {
    expect(statusEmoji(step({ status: 'CURRENT', tatPercent: 40 }))?.glyph).toBe('👏')
  })

  it('draws the thumbs-down for a running step past its TAT', () => {
    expect(statusEmoji(step({ status: 'CURRENT', tatPercent: 110 }))?.glyph).toBe('👎')
  })

  it('draws the crying face for both WAITING and BLOCKED', () => {
    expect(statusEmoji(step({ status: 'WAITING' }))?.glyph).toBe('😢')
    expect(statusEmoji(step({ status: 'BLOCKED' }))?.glyph).toBe('😢')
  })

  it.each([
    ['early', '🙌'],
    ['late', '👎'],
    [null, '👍'],
    [undefined, '👍'],
  ] as const)('draws %s → %s for a DONE step', (closed, glyph) => {
    expect(statusEmoji(step({ status: 'DONE', tatPercent: null, closed }))?.glyph).toBe(glyph)
  })
})

describe('dependencyBadge · §5.6', () => {
  it('names the step depended on', () => {
    expect(dependencyBadge(step({ dependsOnSeqNo: 2 }))).toBe('↳ 2')
  })

  it('marks a dependency-free step as parallel', () => {
    expect(dependencyBadge(step({ dependsOnSeqNo: null }))).toBe('∥')
  })
})

describe('stepAriaLabel', () => {
  it('reads step, owner, state and TAT percent for a running step', () => {
    const label = stepAriaLabel(step({ status: 'CURRENT', tatPercent: 40 }))
    expect(label).toContain('Step 3: Data migration')
    expect(label).toContain('Priya Nair')
    expect(label).toContain('In progress')
    expect(label).toContain('40% of TAT used')
    expect(label).toContain('depends on step 2')
  })

  it('reads the on-time/early/late marker for a DONE step instead of a percent', () => {
    expect(stepAriaLabel(step({ status: 'DONE', tatPercent: null, closed: 'late' }))).toContain('closed delayed')
    expect(stepAriaLabel(step({ status: 'DONE', tatPercent: null, closed: 'early' }))).toContain('completed early')
  })

  it('names an unassigned step by its owner role, not a blank', () => {
    expect(stepAriaLabel(step({ owner: null, ownerRole: 'STEP_OWNER' }))).toContain('STEP_OWNER · unassigned')
  })

  it('appends the hold reason for a blocked step', () => {
    expect(stepAriaLabel(step({ status: 'BLOCKED', note: 'awaiting vendor assets' }))).toContain(
      'blocked: awaiting vendor assets',
    )
  })
})
