import { describe, expect, it } from 'vitest'
import type { HistoryEntry } from '@/api/generated/model/historyEntry'
import type { Ticket } from '@/api/generated/model/ticket'
import {
  ageInDays,
  assignedBy,
  cycleEffortHrs,
  delayInDays,
  effortVariancePct,
  hoursLabel,
  totalEffortHrs,
} from './ticketSummary'

const NOW = new Date('2026-08-14T09:00:00Z')

const ticket = (over: Partial<Ticket> = {}): Ticket => ({
  ticketId: 'CRM-26-00347',
  title: 'Checkout fails',
  level: 'CRITICAL',
  status: 'IN_PROGRESS',
  cycleNo: 2,
  ...over,
})

describe('C-019 · summary panel arithmetic', () => {
  describe('ageInDays', () => {
    it('counts whole calendar days since the ticket was raised', () => {
      expect(ageInDays(ticket({ createdAt: '2026-08-03T09:12:00Z' }), NOW)).toBe(11)
    })

    it('is null when the API omits createdAt rather than reporting an age of 0', () => {
      expect(ageInDays(ticket(), NOW)).toBeNull()
    })
  })

  describe('delayInDays', () => {
    it('measures from delayedSince, which is when the SLA scanner recorded the breach', () => {
      const late = ticket({ isDelayed: true, delayedSince: '2026-08-11T00:15:00Z', plannedCloseDate: '2026-08-01T00:00:00Z' })
      expect(delayInDays(late, NOW)).toBe(3)
    })

    it('falls back to the planned close date when nothing stamped delayedSince', () => {
      expect(delayInDays(ticket({ isDelayed: true, plannedCloseDate: '2026-08-12T12:00:00Z' }), NOW)).toBe(2)
    })

    it('never re-derives lateness itself — a past due date with isDelayed false is not late', () => {
      // The SLA scanner owns this verdict against the working calendar. A
      // second implementation here would call a Friday-evening ticket late on
      // Saturday morning, which is exactly the bug the calendar exists to stop.
      expect(delayInDays(ticket({ isDelayed: false, plannedCloseDate: '2026-08-01T00:00:00Z' }), NOW)).toBeNull()
    })
  })

  describe('effortVariancePct', () => {
    it('reports logged hours against the estimate as a signed percentage', () => {
      expect(effortVariancePct(16, 38)).toBe(138)
      expect(effortVariancePct(16, 8)).toBe(-50)
      expect(effortVariancePct(16, 16)).toBe(0)
    })

    it('is null without an estimate — an unestimated ticket is not "0% over"', () => {
      expect(effortVariancePct(null, 12)).toBeNull()
      expect(effortVariancePct(undefined, 12)).toBeNull()
    })

    it('is null for a zero estimate rather than an infinite percentage', () => {
      expect(effortVariancePct(0, 12)).toBeNull()
    })
  })

  describe('totalEffortHrs', () => {
    const cycles = [
      { cycleNo: 1, totalEffortHrs: 24.5 },
      { cycleNo: 2, totalEffortHrs: 13.5 },
    ]

    it('sums every cycle — blueprint §14 walkthrough A reconciles to 38.0 h', () => {
      expect(totalEffortHrs(ticket(), cycles)).toBe(38)
    })

    it('does not shrink when an earlier cycle is selected', () => {
      // The regression this guards: `effortLogs` in the payload is cycle-scoped,
      // so a total summed from there would drop to 24.5 the moment a reader
      // clicked "Cycle 1" — the same cross-cycle contamination, mirrored.
      expect(totalEffortHrs(ticket({ cycleNo: 1 }), cycles)).toBe(38)
    })

    it('falls back to the ticket total when cycles are absent, which the contract allows', () => {
      expect(totalEffortHrs(ticket({ totalEffortHrs: 7.5 }), undefined)).toBe(7.5)
      expect(totalEffortHrs(ticket({ totalEffortHrs: 7.5 }), [])).toBe(7.5)
    })

    it('rounds to one decimal so floating-point addition never leaks into the panel', () => {
      expect(totalEffortHrs(ticket(), [{ cycleNo: 1, totalEffortHrs: 0.1 }, { cycleNo: 2, totalEffortHrs: 0.2 }])).toBe(0.3)
    })
  })

  describe('cycleEffortHrs', () => {
    it('reads one cycle out of the list, and 0 for a cycle that is not there', () => {
      const cycles = [{ cycleNo: 1, totalEffortHrs: 24.5 }]
      expect(cycleEffortHrs(cycles, 1)).toBe(24.5)
      expect(cycleEffortHrs(cycles, 2)).toBe(0)
      expect(cycleEffortHrs(undefined, 1)).toBe(0)
    })
  })

  describe('assignedBy', () => {
    const entry = (over: Partial<HistoryEntry>): HistoryEntry => ({
      action: 'FIELD_CHANGED',
      cycleNo: 2,
      iterationNo: 1,
      ...over,
    })

    it('names the actor on the most recent assignee change', () => {
      const history = [
        entry({ fieldName: 'assigneeId', actor: { id: 2, displayName: 'Meera Iyer' } }),
        entry({ fieldName: 'level', actor: { id: 1, displayName: 'Anita Rao' } }),
        entry({ fieldName: 'assigneeId', actor: { id: 1, displayName: 'Anita Rao' } }),
      ]
      expect(assignedBy(history)?.displayName).toBe('Anita Rao')
    })

    it('is null when nobody has reassigned it — the CREATED row is not an assignment', () => {
      expect(assignedBy([entry({ action: 'CREATED', actor: { id: 6, displayName: 'Priya Nair' } })])).toBeNull()
      expect(assignedBy(undefined)).toBeNull()
    })

    it('ignores a system entry with no actor rather than throwing on it', () => {
      expect(assignedBy([entry({ fieldName: 'assigneeId', actorType: 'SYSTEM' })])).toBeNull()
    })
  })

  describe('hoursLabel', () => {
    it('formats to one decimal, and shows an em dash for absent rather than 0.0 h', () => {
      expect(hoursLabel(38)).toBe('38.0 h')
      expect(hoursLabel(0)).toBe('0.0 h')
      expect(hoursLabel(null)).toBe('—')
      expect(hoursLabel(undefined)).toBe('—')
    })
  })
})
