import { describe, expect, it } from 'vitest'

import type { ObClient } from '@/api/generated/model/obClient'

import { healthChip, journeyProgress, productSummary, statusChip } from './obClientRow'

/**
 * B-108 · the cell logic OB-03's grid is mostly made of.
 *
 * Pure functions, tested without a render, because the interesting part is the
 * reconciliation of three server fields into one column and not the markup —
 * `journeyStrip.test.ts` splits the same way for the same reason.
 */
function client(over: Partial<ObClient> = {}): ObClient {
  return {
    id: 1,
    name: 'Northwind Technologies Pvt Ltd',
    onboardingDate: '2026-07-14',
    status: 'ONBOARDING',
    gateStatus: 'OPEN',
    journeyCount: 2,
    ...over,
  }
}

describe('healthChip', () => {
  it('renders a locked gate as “Prerequisites pending”, which is §9’s own label', () => {
    expect(healthChip(client({ gateStatus: 'LOCKED', rag: null })).label).toBe(
      'Prerequisites pending',
    )
  })

  /**
   * The one that keeps `ObRag`'s decision honest.
   *
   * The colour is null while the gate is locked *today*, and this asserts the
   * screen would still say the right thing if a server ever sent both — the
   * gate is the actionable fact and the colour would be describing a clock that
   * is not running.
   */
  it('prefers the gate over a colour if both somehow arrive', () => {
    expect(healthChip(client({ gateStatus: 'LOCKED', rag: 'RED' })).label).toBe(
      'Prerequisites pending',
    )
  })

  it('uses the module’s own colour vocabulary, not GREEN/AMBER/RED', () => {
    expect(healthChip(client({ rag: 'GREEN' })).label).toBe('On track')
    expect(healthChip(client({ rag: 'AMBER' })).label).toBe('At risk')
    expect(healthChip(client({ rag: 'RED' })).label).toBe('Breached')
  })

  it('maps each colour to the chip variant the rest of the module uses', () => {
    expect(healthChip(client({ rag: 'GREEN' })).variant).toBe('success')
    expect(healthChip(client({ rag: 'AMBER' })).variant).toBe('warning')
    expect(healthChip(client({ rag: 'RED' })).variant).toBe('danger')
  })

  /**
   * An open gate and no colour is a finished client, not a broken row. It gets
   * a neutral chip rather than a red one, and a distinct label from the locked
   * case — the two are different facts and a reader has to be able to act on
   * the difference.
   */
  it('says “Not started” for an open gate with nothing running', () => {
    const chip = healthChip(client({ gateStatus: 'OPEN', rag: null }))
    expect(chip.label).toBe('Not started')
    expect(chip.variant).toBe('neutral')
  })

  it('treats an absent rag exactly as an explicit null', () => {
    expect(healthChip(client({ gateStatus: 'OPEN' }))).toEqual(
      healthChip(client({ gateStatus: 'OPEN', rag: null })),
    )
  })
})

describe('statusChip', () => {
  it('gives LIVE its own colour, so it is never mistaken for health', () => {
    expect(statusChip('LIVE')).toEqual({ label: 'Live', variant: 'success' })
  })

  it('reads a hold as a warning and a drop as a failure', () => {
    expect(statusChip('ON_HOLD').variant).toBe('warning')
    expect(statusChip('DROPPED').variant).toBe('danger')
  })
})

describe('journeyProgress', () => {
  it('reads complete over bought', () => {
    expect(journeyProgress(client({ journeyCount: 5, journeysComplete: 2 })).label).toBe('2 / 5')
  })

  /**
   * `journeysComplete` is optional in the contract. Defaulting it to zero is
   * the deliberate choice — the alternative is a blank column on every row —
   * and this pins it so a later refactor cannot quietly turn it into "—".
   */
  it('reads an absent journeysComplete as none complete, not as unknown', () => {
    expect(journeyProgress(client({ journeyCount: 3, journeysComplete: undefined })).label).toBe(
      '0 / 3',
    )
  })

  it('says a client with no products has no journeys rather than “0 / 0”', () => {
    expect(journeyProgress(client({ journeyCount: 0 })).label).toBe('No products')
  })

  it('does not pluralise a single journey in the hint', () => {
    expect(journeyProgress(client({ journeyCount: 1, journeysComplete: 1 })).hint).toContain(
      '1 journey complete',
    )
  })
})

describe('productSummary', () => {
  const products = [
    { id: 1, code: 'ERP', name: 'ERP Suite' },
    { id: 2, code: 'ATT', name: 'Attendance' },
    { id: 3, code: 'LMS', name: 'Learning' },
    { id: 4, code: 'FEE', name: 'Fees' },
  ]

  it('shows the first two and counts the rest', () => {
    const summary = productSummary(client({ products }))
    expect(summary.shown).toEqual(['ERP Suite', 'Attendance'])
    expect(summary.overflow).toBe(2)
  })

  it('names every product in the title, so the truncation is recoverable', () => {
    expect(productSummary(client({ products })).title).toBe(
      'ERP Suite, Attendance, Learning, Fees',
    )
  })

  it('never reports a negative overflow when there is nothing to truncate', () => {
    expect(productSummary(client({ products: products.slice(0, 1) })).overflow).toBe(0)
    expect(productSummary(client({ products: [] })).overflow).toBe(0)
    expect(productSummary(client({})).shown).toEqual([])
  })
})
