import { describe, expect, it } from 'vitest'
import type { Ticket } from '@/api/generated/model/ticket'
import { rowCueClassName } from './columns'

/** Only `level` and `isDelayed` are exercised — the rest is boilerplate to satisfy the type. */
function ticket(overrides: Partial<Ticket>): Ticket {
  return {
    ticketId: 'CRM-26-00001',
    title: 'Fixture ticket',
    level: 'MEDIUM',
    status: 'NEW',
    cycleNo: 1,
    isDelayed: false,
    ...overrides,
  }
}

describe('C-016 row colour cue', () => {
  it('gives a critical ticket a soft red left border', () => {
    expect(rowCueClassName(ticket({ level: 'CRITICAL' }))).toBe('border-l-4 border-l-level-critical')
  })

  it('gives a delayed, non-critical ticket a soft amber left border', () => {
    expect(rowCueClassName(ticket({ level: 'HIGH', isDelayed: true }))).toBe('border-l-4 border-l-level-high')
  })

  it('prefers the critical cue when a ticket is both delayed and critical', () => {
    expect(rowCueClassName(ticket({ level: 'CRITICAL', isDelayed: true }))).toBe('border-l-4 border-l-level-critical')
  })

  it('gives an on-track, non-critical ticket no cue', () => {
    expect(rowCueClassName(ticket({ level: 'LOW', isDelayed: false }))).toBeUndefined()
  })
})
