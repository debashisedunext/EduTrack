import { describe, expect, it } from 'vitest'

import type { Ticket } from '@/api/generated/model/ticket'
import type { BulkResultResponseData } from '@/api/generated/model/bulkResultResponseData'

import {
  alreadyClosedCount,
  canBulkAct,
  closableIds,
  failedResults,
  selectionAfter,
  summariseBulkResult,
} from './bulkActions'

function ticket(ticketId: string, status: Ticket['status']): Ticket {
  return { ticketId, title: `Ticket ${ticketId}`, level: 'MEDIUM', status, cycleNo: 1 }
}

describe('canBulkAct', () => {
  it('admits PM and Admin', () => {
    expect(canBulkAct('ADMIN')).toBe(true)
    expect(canBulkAct('PM')).toBe(true)
  })

  it('refuses the four roles blueprint §7.5 leaves out', () => {
    expect(canBulkAct('SUPPORT')).toBe(false)
    expect(canBulkAct('DEVELOPER')).toBe(false)
    expect(canBulkAct('QA')).toBe(false)
    expect(canBulkAct('DEPLOYMENT')).toBe(false)
  })

  it('refuses while the role is still unknown', () => {
    // The safe direction. A column that appears a beat after the grid shifts
    // every other column sideways, and a Developer who sees a Close button for
    // half a second has been told something untrue about their permissions.
    expect(canBulkAct(undefined)).toBe(false)
  })
})

describe('closableIds', () => {
  const visible = [ticket('T-1', 'NEW'), ticket('T-2', 'CLOSED'), ticket('T-3', 'IN_PROGRESS')]

  it('drops the rows already closed on screen', () => {
    expect(closableIds(new Set(['T-1', 'T-2', 'T-3']), visible)).toEqual(['T-1', 'T-3'])
    expect(alreadyClosedCount(new Set(['T-1', 'T-2', 'T-3']), visible)).toBe(1)
  })

  it('keeps ids the grid cannot see', () => {
    // Selection survives paging, so a ticket ticked two pages back has no row
    // here to read a status from. Excluding it would silently shrink a batch
    // the user assembled deliberately — the server is the authority on those.
    expect(closableIds(new Set(['T-1', 'T-99']), visible)).toEqual(['T-1', 'T-99'])
    expect(alreadyClosedCount(new Set(['T-1', 'T-99']), visible)).toBe(0)
  })

  it('reports an all-closed selection as having nothing to send', () => {
    expect(closableIds(new Set(['T-2']), visible)).toEqual([])
    expect(alreadyClosedCount(new Set(['T-2']), visible)).toBe(1)
  })
})

describe('summariseBulkResult', () => {
  it('spells the singular out rather than suffixing (s)', () => {
    // Read aloud on a role="status" region, where "1 ticket(s) closed" is what
    // it sounds like.
    expect(summariseBulkResult({ succeeded: 1, failed: 0, results: [] }, 'closed')).toBe(
      '1 ticket closed',
    )
    expect(summariseBulkResult({ succeeded: 4, failed: 0, results: [] }, 'closed')).toBe(
      '4 tickets closed',
    )
  })

  it('names the refusals when there are any', () => {
    expect(summariseBulkResult({ succeeded: 38, failed: 2, results: [] }, 'closed')).toBe(
      '38 tickets closed · 2 refused',
    )
  })

  it('survives a response that omits the counts', () => {
    expect(summariseBulkResult({}, 'updated')).toBe('0 tickets updated')
  })
})

describe('failedResults', () => {
  it('keeps only the refusals', () => {
    const result: BulkResultResponseData = {
      succeeded: 1,
      failed: 1,
      results: [
        { ticketId: 'T-1', ok: true, reason: null },
        { ticketId: 'T-2', ok: false, reason: 'Already closed' },
      ],
    }
    expect(failedResults(result).map((r) => r.ticketId)).toEqual(['T-2'])
  })
})

describe('selectionAfter', () => {
  it('releases what succeeded and holds on to what was refused', () => {
    // The refused rows stay ticked because the user has something left to do
    // about them; clearing them would hide the failure the moment the dialog
    // closed, and the grid refetch would blend them back in unfindably.
    const next = selectionAfter(new Set(['T-1', 'T-2', 'T-3']), {
      succeeded: 2,
      failed: 1,
      results: [
        { ticketId: 'T-1', ok: true, reason: null },
        { ticketId: 'T-2', ok: false, reason: 'Already closed' },
        { ticketId: 'T-3', ok: true, reason: null },
      ],
    })
    expect([...next]).toEqual(['T-2'])
  })

  it('leaves the selection alone when the response carries no rows', () => {
    const selected = new Set(['T-1'])
    expect([...selectionAfter(selected, { succeeded: 0, failed: 0 })]).toEqual(['T-1'])
  })
})
