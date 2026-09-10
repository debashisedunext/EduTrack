import { describe, expect, it } from 'vitest'

import type { ObJourneyStrip, ObStepDot } from '@/api/generated/model'

import { findProductGroup, groupJourneysByProduct, openEscalationCount } from './productGroups'

/**
 * The fold from journeys to products, tested against the case that made it
 * necessary: **one product publishing several Module Services**.
 *
 * The MSW fixtures give every client one journey per product, because they
 * were written when that was the only shape a client could have. The live
 * backend no longer agrees — the corpus seeds ERP's "Enterprise (with data
 * migration audit)" service live beside its standard one, so a real ERP client
 * carries two journeys of one product — and a grouping only ever exercised on
 * the one-to-one fixtures would be a grouping nobody had run.
 *
 * So the multi-service assertions are built here rather than seeded there. A
 * second ERP journey added to `mocks/db.ts` would change what a dozen OB-03,
 * OB-05 and dashboard tests count, none of which are about this.
 */

const ERP = { id: 1, code: 'ERP', name: 'EduTrack ERP' }
const BIOMETRIC = { id: 2, code: 'BIOMETRIC', name: 'Biometric Attendance' }

let nextStepId = 1

/** `n` services, the first `settled` of them done. */
function steps(n: number, settled = 0): ObStepDot[] {
  return Array.from({ length: n }, (_, i) => ({
    id: nextStepId++,
    sequence: i + 1,
    name: `Service ${i + 1}`,
    status: i < settled ? 'DONE' : 'PENDING',
  }))
}

function strip(overrides: Partial<ObJourneyStrip> & { id: number }): ObJourneyStrip {
  return {
    product: ERP,
    serviceName: 'Standard SaaS onboarding',
    gateStatus: 'OPEN',
    percentComplete: 0,
    steps: [],
    ...overrides,
  }
}

describe('groupJourneysByProduct', () => {
  it('gives one group per product, however many services the product runs', () => {
    const groups = groupJourneysByProduct([
      strip({ id: 11, serviceName: 'Standard SaaS onboarding', steps: steps(8, 8) }),
      strip({ id: 12, serviceName: 'Enterprise (data migration)', steps: steps(4, 2) }),
      strip({ id: 21, product: BIOMETRIC, steps: steps(5, 1) }),
    ])

    expect(groups.map((g) => g.product.name)).toEqual(['EduTrack ERP', 'Biometric Attendance'])
    expect(groups[0].journeys.map((j) => j.id)).toEqual([11, 12])
    expect(groups[1].journeys.map((j) => j.id)).toEqual([21])
  })

  /**
   * The grouping key is the product **id**. A renamed product must not split
   * into two cards, and two products that happen to share a display string
   * must not merge into one — which is what keying on the name would do in
   * both directions.
   */
  it('keys on the product id, not its name', () => {
    const groups = groupJourneysByProduct([
      strip({ id: 11, product: { ...ERP, name: 'EduTrack ERP' } }),
      strip({ id: 12, product: { ...ERP, name: 'EduTrack ERP (renamed)' } }),
      strip({ id: 31, product: { id: 3, code: 'LMS', name: 'EduTrack ERP' } }),
    ])

    expect(groups).toHaveLength(2)
    expect(groups[0].journeys.map((j) => j.id)).toEqual([11, 12])
  })

  /**
   * The client document returns journeys "in the admin-ordered service
   * sequence", which OB-07's ↑/↓ controls exist to set. Sorting here — by name,
   * by progress, by anything — would silently overrule it.
   */
  it('keeps the order the journeys arrived in', () => {
    const groups = groupJourneysByProduct([
      strip({ id: 21, product: BIOMETRIC }),
      strip({ id: 11 }),
    ])
    expect(groups.map((g) => g.product.code)).toEqual(['BIOMETRIC', 'ERP'])
  })

  /**
   * Settled services over total across the product, **not the mean of its
   * journeys' percentages**. Nine services with one done is 11%; averaging a
   * finished two-service journey with an untouched seven-service one would
   * report 50%, and a reader would open a card expecting half a product built.
   */
  it('counts services across the product rather than averaging its journeys', () => {
    const [erp] = groupJourneysByProduct([
      strip({ id: 11, steps: steps(2, 2), percentComplete: 100 }),
      strip({ id: 12, steps: steps(7, 0), percentComplete: 0 }),
    ])

    expect(erp.servicesTotal).toBe(9)
    expect(erp.servicesSettled).toBe(2)
    expect(erp.percentComplete).toBe(22)
  })

  /** A waived service is a settled one — what `ob-signoff-journey-incomplete`
   * counts server-side, and what the accordion's own completion check agrees
   * with. */
  it('counts a skipped service as settled', () => {
    const [erp] = groupJourneysByProduct([
      strip({
        id: 11,
        steps: [
          { id: 901, sequence: 1, name: 'Kickoff', status: 'DONE' },
          { id: 902, sequence: 2, name: 'Waived', status: 'SKIPPED' },
          { id: 903, sequence: 3, name: 'Training', status: 'IN_PROGRESS' },
        ],
      }),
    ])

    expect(erp.servicesSettled).toBe(2)
    expect(erp.percentComplete).toBe(67)
  })

  it('reports the worst RAG any of the product journeys carries', () => {
    const [erp] = groupJourneysByProduct([
      strip({ id: 11, rag: 'GREEN' }),
      strip({ id: 12, rag: 'RED' }),
      strip({ id: 13, rag: 'AMBER' }),
    ])
    expect(erp.rag).toBe('RED')
  })

  /**
   * `ObJourneyStrip.rag` is null while a journey is locked or unstarted —
   * "nothing is running to colour". A group must not read that absence as a
   * colour of its own, in either direction: an unstarted sibling cannot drag a
   * breached product back to green, and it cannot invent a red either.
   */
  it('ignores journeys with no RAG, and carries none when nobody has one', () => {
    const [mixed] = groupJourneysByProduct([
      strip({ id: 11, rag: 'AMBER' }),
      strip({ id: 12, gateStatus: 'LOCKED' }),
    ])
    expect(mixed.rag).toBe('AMBER')

    const [locked] = groupJourneysByProduct([strip({ id: 11, gateStatus: 'LOCKED' })])
    expect(locked.rag).toBeNull()
  })

  /**
   * The hold is what stops the **whole** product, so one free journey clears
   * it. A card reading "prerequisites pending" over a product whose second
   * service is already in flight would send a reader to clear a gate that is
   * holding nothing.
   */
  it('holds only while every journey of the product is held', () => {
    const [partly] = groupJourneysByProduct([
      strip({ id: 11, gateStatus: 'LOCKED' }),
      strip({ id: 12 }),
    ])
    expect(partly.hold).toBeNull()

    const [allLocked] = groupJourneysByProduct([
      strip({ id: 11, gateStatus: 'LOCKED' }),
      strip({ id: 12, gateStatus: 'LOCKED' }),
    ])
    expect(allLocked.hold).toBe('GATE_LOCKED')
  })

  /** The gate first, `journeyHold`'s own precedence: it is the one a reader
   * can go and act on. */
  it('names the gate ahead of a sibling hold when both apply', () => {
    const [group] = groupJourneysByProduct([
      strip({ id: 11, gateStatus: 'LOCKED' }),
      strip({ id: 12, heldByJourneyId: 11 }),
    ])
    expect(group.hold).toBe('GATE_LOCKED')

    const [held] = groupJourneysByProduct([strip({ id: 12, heldByJourneyId: 99 })])
    expect(held.hold).toBe('HELD_BY_SIBLING')
  })

  it('sums the TAT budget and the consumption across the product', () => {
    const [erp] = groupJourneysByProduct([
      strip({ id: 11, totalTatDays: 6, utilizedHours: 24 }),
      strip({ id: 12, totalTatDays: 4, utilizedHours: 8 }),
    ])
    expect(erp.tat).toEqual({ usedDays: 4, totalDays: 10, percent: 40, band: 'ok' })
  })

  /**
   * `tatUsage`'s "0/0 is not 0% used", one level up. A product whose template
   * pinned no TATs and a product nobody has touched are different facts, and a
   * bar reading 0/0 asserts the second about the first.
   */
  it('reports no TAT figure when no journey of the product has a budget', () => {
    const [erp] = groupJourneysByProduct([strip({ id: 11, utilizedHours: 3 })])
    expect(erp.tat).toBeNull()
  })

  it('is empty for a client who has bought nothing', () => {
    expect(groupJourneysByProduct([])).toEqual([])
  })
})

describe('findProductGroup', () => {
  const journeys = [strip({ id: 11 }), strip({ id: 21, product: BIOMETRIC })]

  it('finds the product by id', () => {
    expect(findProductGroup(journeys, 2)?.product.code).toBe('BIOMETRIC')
  })

  /** Undefined, not an empty group: the page turns this into "product not
   * found" and a way back, rather than a page of empty accordions. */
  it('answers undefined for a product this client never bought', () => {
    expect(findProductGroup(journeys, 3)).toBeUndefined()
  })
})

describe('openEscalationCount', () => {
  it('counts only the escalations sitting on this product services', () => {
    const [erp, biometric] = groupJourneysByProduct([
      strip({
        id: 11,
        steps: [
          { id: 101, sequence: 1, name: 'Kickoff', status: 'DONE' },
          { id: 102, sequence: 2, name: 'Migration', status: 'IN_PROGRESS' },
        ],
      }),
      strip({
        id: 21,
        product: BIOMETRIC,
        steps: [{ id: 201, sequence: 1, name: 'Dispatch', status: 'PENDING' }],
      }),
    ])

    const open = new Set([102, 201])
    expect(openEscalationCount(erp, open)).toBe(1)
    expect(openEscalationCount(biometric, open)).toBe(1)
    expect(openEscalationCount(erp, new Set())).toBe(0)
    expect(openEscalationCount(erp, undefined)).toBe(0)
  })
})
