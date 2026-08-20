import { describe, expect, it } from 'vitest'

import type { RibbonSegment } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { SEGMENT_TREATMENT, ownerLabel, segmentAriaLabel, treatmentFor } from './segmentState'

/**
 * The vocabulary, tested without a DOM. What is asserted is what a reader is
 * *told* — the words, and that every state has a distinguishing word at all.
 * Which Tailwind class carries which colour is not asserted: it changes with a
 * restyle and pinning it would break on a change that is not a defect.
 */

function seg(over: Partial<RibbonSegment> = {}): RibbonSegment {
  return {
    stageCode: 'DEVELOPMENT',
    displayName: 'Development',
    state: SegmentState.COMPLETED,
    sequence: 3,
    owner: { id: 7, displayName: 'Ravi Kumar' },
    ownerRole: 'DEVELOPER',
    enteredAt: '2026-08-01T09:00:00Z',
    exitedAt: '2026-08-03T13:00:00Z',
    durationMins: 2940,
    effortHrs: 14.5,
    idleMins: 2070,
    iterationNo: 1,
    loopBackCount: 0,
    ...over,
  }
}

describe('SEGMENT_TREATMENT', () => {
  it('covers all six states in the contract', () => {
    expect(Object.keys(SEGMENT_TREATMENT).sort()).toEqual(Object.keys(SegmentState).sort())
  })

  it('gives every state its own word, so colour is never the only signal', () => {
    const labels = Object.values(SEGMENT_TREATMENT).map((t) => t.label)
    expect(new Set(labels).size).toBe(labels.length)
  })

  it('gives every state its own icon', () => {
    const icons = Object.values(SEGMENT_TREATMENT).map((t) => t.Icon)
    expect(new Set(icons).size).toBe(icons.length)
  })

  it('strikes through only the skipped stage name', () => {
    const struck = Object.entries(SEGMENT_TREATMENT)
      .filter(([, t]) => t.title.includes('line-through'))
      .map(([state]) => state)
    expect(struck).toEqual([SegmentState.SKIPPED])
  })

  it('uses only design tokens, never a raw colour', () => {
    const classes = Object.values(SEGMENT_TREATMENT)
      .flatMap((t) => [t.card, t.title, t.connector])
      .join(' ')
    expect(classes).not.toMatch(/#[0-9a-f]{3,8}\b/i)
    expect(classes).not.toMatch(/\b(?:bg|text|border|ring)-(?:red|green|blue|amber|indigo|emerald|gray|grey|slate|zinc)-\d/)
  })
})

describe('treatmentFor', () => {
  it('resolves each of the six', () => {
    for (const state of Object.values(SegmentState)) {
      expect(treatmentFor(state)).toBe(SEGMENT_TREATMENT[state])
    }
  })

  // A wire enum can grow a seventh value server-side. Neutral, not a crash.
  it('falls back to pending for a state this client does not know', () => {
    expect(treatmentFor('ESCALATED' as SegmentState)).toBe(SEGMENT_TREATMENT.PENDING)
    expect(treatmentFor(undefined)).toBe(SEGMENT_TREATMENT.PENDING)
  })
})

describe('ownerLabel', () => {
  it('names the person who held the stage', () => {
    expect(ownerLabel(seg())).toBe('Ravi Kumar')
  })

  // "Waiting for the QA team" and "nobody is on this" are different facts.
  it('names the owning role when nobody has held the stage yet', () => {
    expect(ownerLabel(seg({ owner: undefined, ownerRole: 'QA', state: SegmentState.PENDING })))
      .toBe('QA · unassigned')
  })

  it('falls back to Unassigned when there is no role either', () => {
    expect(ownerLabel(seg({ owner: undefined, ownerRole: undefined }))).toBe('Unassigned')
  })
})

describe('segmentAriaLabel', () => {
  // §4A.3: stage, owner, state and effort — plus the duration, because the
  // queue insight in §4A.4 is the ratio between the two numbers.
  it('reads stage, owner, state, duration and effort in order', () => {
    expect(segmentAriaLabel(seg())).toBe(
      'Development, Ravi Kumar, completed, 2d 1h in stage, 14.5 h effort',
    )
  })

  it('says "current stage" rather than the visual "Now" label', () => {
    expect(segmentAriaLabel(seg({ state: SegmentState.CURRENT }))).toContain('current stage')
    expect(segmentAriaLabel(seg({ state: SegmentState.CURRENT }))).not.toContain('now')
  })

  it('prefers the live elapsed figure when one is passed', () => {
    const label = segmentAriaLabel(seg({ state: SegmentState.CURRENT, durationMins: null }), 135)
    expect(label).toContain('2h 15m in stage')
  })

  it('announces the loop count, singular and plural', () => {
    expect(segmentAriaLabel(seg({ loopBackCount: 1 }))).toContain('returned 1 time')
    expect(segmentAriaLabel(seg({ loopBackCount: 2 }))).toContain('returned 2 times')
  })

  it('omits the loop clause when the stage never bounced', () => {
    expect(segmentAriaLabel(seg({ loopBackCount: 0 }))).not.toContain('returned')
  })

  it('announces the skip reason, which is otherwise hover-only', () => {
    const label = segmentAriaLabel(seg({ state: SegmentState.SKIPPED, skipReason: 'no QA needed' }))
    expect(label).toContain('skipped because no QA needed')
  })

  it('survives a segment with nothing but a stage code', () => {
    expect(segmentAriaLabel({ stageCode: 'QA' })).toBe(
      'QA, Unassigned, pending, — in stage, — effort',
    )
  })
})
