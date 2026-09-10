import { describe, expect, it } from 'vitest'

import {
  defaultOpenJourneyId,
  formatDays,
  formatTatUsage,
  journeyHold,
  prereqProgress,
  ragLabel,
  ragVariant,
  tatUsage,
} from './journeyStrip'

/**
 * C-110 · the arithmetic behind OB-05's strips.
 *
 * Every case here is one the screen actually meets in the seeded data or one
 * the module plan rules on explicitly. Nothing asserts a Tailwind class — the
 * band is the decision, and which colour renders it is the component's.
 */
describe('tatUsage', () => {
  it('divides utilized hours into working days, not calendar ones', () => {
    // Northwind's ERP journey: 24 days of budget, 128.5 hours consumed.
    const usage = tatUsage({ totalTatDays: 24, utilizedHours: 128.5 })
    expect(usage?.usedDays).toBeCloseTo(16.0625)
    expect(usage?.totalDays).toBe(24)
  })

  /**
   * The three bands §10 asks for, at their exact boundaries. 75 is amber and
   * 100 is over — a threshold that admitted its own value as "still fine"
   * would report the breach one tick late, every time.
   */
  it.each([
    [40, 'ok'],
    [74.9, 'ok'],
    [75, 'amber'],
    [99.9, 'amber'],
    [100, 'over'],
    [180, 'over'],
  ])('puts %s%% of budget in the %s band', (percent, band) => {
    const usage = tatUsage({ totalTatDays: 10, utilizedHours: (percent / 100) * 10 * 8 })
    expect(usage?.band).toBe(band)
  })

  /**
   * A journey with no budget is not a journey at nought percent.
   *
   * A template with no TATs pinned and a journey nobody has started are
   * different facts, and `0 / 0d TAT` asserts the second about the first. The
   * strip prints "No TAT budget" instead, which it can only do if this returns
   * null rather than a zeroed object.
   */
  it.each([
    { totalTatDays: 0, utilizedHours: 0 },
    { totalTatDays: undefined, utilizedHours: 12 },
  ])('has nothing to report when there is no budget: %o', (journey) => {
    expect(tatUsage(journey)).toBeNull()
  })

  it('treats a journey with a budget and no clock as zero used, not as no budget', () => {
    // Acme's locked journey — instantiated, fully visible, clocks dead.
    const usage = tatUsage({ totalTatDays: 24, utilizedHours: 0 })
    expect(usage).not.toBeNull()
    expect(usage?.band).toBe('ok')
    expect(formatTatUsage(usage!)).toBe('0 / 24d TAT')
  })
})

describe('formatDays', () => {
  /**
   * One decimal, and no trailing `.0`. 19 hours against a 3-day budget is 2.4
   * days: printing 2 rounds a quarter of the figure away, and printing 2.375
   * claims a precision `utilizedHours` does not have.
   */
  it.each([
    [2.375, '2.4'],
    [24, '24'],
    [24.04, '24'],
    [0, '0'],
    [16.0625, '16.1'],
  ])('formats %s as %s', (input, expected) => {
    expect(formatDays(input)).toBe(expected)
  })
})

describe('journeyHold', () => {
  /**
   * A journey can be past the client's gate and still held behind a sibling
   * (plan §5.5) — two different holds, and the screen has to say which. A
   * reader told "prerequisites pending" about a journey whose prerequisites
   * are cleared will go and clear them again.
   */
  it('reports the gate before a sibling when both are true', () => {
    expect(journeyHold({ gateStatus: 'LOCKED', heldByJourneyId: 7 })).toBe('GATE_LOCKED')
  })

  it('reports a sibling hold on a journey that is past the gate', () => {
    expect(journeyHold({ gateStatus: 'OPEN', heldByJourneyId: 1 })).toBe('HELD_BY_SIBLING')
  })

  it('reports nothing holding a running journey', () => {
    expect(journeyHold({ gateStatus: 'OPEN', heldByJourneyId: null })).toBeNull()
  })
})

describe('rag presentation', () => {
  it.each([
    ['GREEN', 'success', 'On track'],
    ['AMBER', 'warning', 'At risk'],
    ['RED', 'danger', 'Breached'],
  ] as const)('renders %s as %s / %s', (rag, variant, label) => {
    expect(ragVariant(rag)).toBe(variant)
    expect(ragLabel(rag)).toBe(label)
  })

  /**
   * Null is the absence of anything running to colour, not a fourth colour —
   * `ObRag`'s own contract. It gets the neutral chip and the words "Not
   * started", never green.
   */
  it('does not colour a journey with no health to report', () => {
    expect(ragVariant(null)).toBe('neutral')
    expect(ragVariant(undefined)).toBe('neutral')
    expect(ragLabel(null)).toBe('Not started')
  })
})

describe('prereqProgress', () => {
  it('counts verified mandatory tasks against the mandatory total', () => {
    const progress = prereqProgress({
      mandatoryTotal: 4,
      mandatoryVerified: 3,
      optionalOutstanding: 0,
      gateStatus: 'LOCKED',
    })
    expect(progress).toMatchObject({ verified: 3, total: 4, percent: 75, isCleared: false })
  })

  /**
   * The case `optionalOutstanding` exists for: every mandatory task verified,
   * gate still locked. Without the optional count beside it the bar reads
   * "4/4" next to "Gate locked" and looks like a bug.
   */
  it('carries the optional outstanding count alongside a full mandatory bar', () => {
    const progress = prereqProgress({
      mandatoryTotal: 4,
      mandatoryVerified: 4,
      optionalOutstanding: 2,
      gateStatus: 'LOCKED',
    })
    expect(progress.percent).toBe(100)
    expect(progress.isCleared).toBe(false)
    expect(progress.optionalOutstanding).toBe(2)
  })

  /**
   * `isCleared` is read from the gate and never from the bar, for the reason
   * above: they disagree legitimately, and the gate is the one that decides
   * whether anything below can run.
   */
  it('reads cleared from the gate, not from the bar', () => {
    const progress = prereqProgress({
      mandatoryTotal: 0,
      mandatoryVerified: 0,
      optionalOutstanding: 0,
      gateStatus: 'OPEN',
    })
    expect(progress.percent).toBe(100)
    expect(progress.isCleared).toBe(true)
  })
})

describe('defaultOpenJourneyId', () => {
  const j = (
    id: number,
    over: { gateStatus?: 'OPEN' | 'LOCKED'; heldByJourneyId?: number | null; percentComplete?: number } = {},
  ) => ({
    id,
    gateStatus: over.gateStatus ?? ('OPEN' as const),
    heldByJourneyId: over.heldByJourneyId ?? null,
    percentComplete: over.percentComplete ?? 0,
  })

  it('opens nothing when there is nothing to open', () => {
    expect(defaultOpenJourneyId([])).toBeUndefined()
  })

  /**
   * The running one, not merely the first — a finished service followed by a
   * mid-flight one should open on the one somebody still has work in.
   */
  it('opens the running journey rather than the first listed', () => {
    expect(defaultOpenJourneyId([j(11, { percentComplete: 100 }), j(12)])).toBe(12)
  })

  it('skips a locked journey and one held behind a sibling', () => {
    expect(defaultOpenJourneyId([j(11, { gateStatus: 'LOCKED' }), j(12)])).toBe(12)
    expect(defaultOpenJourneyId([j(11, { heldByJourneyId: 9 }), j(12)])).toBe(12)
  })

  /**
   * Nothing running is the finished client and the locked one, and both still
   * get a ribbon: a page with one beats a page without one, which is the
   * prototype's own behaviour.
   */
  it('falls back to the first journey when none is running', () => {
    expect(defaultOpenJourneyId([j(11, { percentComplete: 100 }), j(12, { percentComplete: 100 })])).toBe(11)
    expect(defaultOpenJourneyId([j(71, { gateStatus: 'LOCKED' })])).toBe(71)
  })
})
