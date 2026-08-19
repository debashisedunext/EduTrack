import { describe, expect, it } from 'vitest'

import type { HistoryEntry } from '@/api/generated/model/historyEntry'
import { describeHistoryEntry } from './historyEntryText'

function entry(overrides: Partial<HistoryEntry>): HistoryEntry {
  return { id: 1, action: 'FIELD_CHANGED', createdAt: '2026-08-13T10:15:00Z', ...overrides }
}

describe('describeHistoryEntry', () => {
  it('describes a level change with both values', () => {
    expect(describeHistoryEntry(entry({ action: 'LEVEL_CHANGED', oldValue: 'HIGH', newValue: 'CRITICAL' })))
      .toBe('Level changed HIGH → CRITICAL')
  })

  it('describes a status change with both values', () => {
    expect(describeHistoryEntry(entry({ action: 'STATUS_CHANGED', oldValue: 'OPEN', newValue: 'RESOLVED' })))
      .toBe('Status changed OPEN → RESOLVED')
  })

  it('names the destination stage when the server sent one', () => {
    expect(describeHistoryEntry(entry({ action: 'STAGE_ADVANCED', oldValue: 'DEVELOPMENT', newValue: 'QA' })))
      .toBe('Moved on — DEVELOPMENT → QA')
  })

  /**
   * `ticket_history` only ever stamps the stage that was left — see this
   * module's own note. A row with no `newValue` must still read as a
   * sentence, not as "undefined".
   */
  it('falls back to naming only the stage left when there is no destination', () => {
    expect(describeHistoryEntry(entry({ action: 'STAGE_ADVANCED', oldValue: 'DEVELOPMENT', newValue: undefined })))
      .toBe('Moved on from DEVELOPMENT')
  })

  it('describes a rework the same way, with its own verb', () => {
    expect(describeHistoryEntry(entry({ action: 'REWORK', oldValue: 'QA', newValue: undefined })))
      .toBe('Sent back from QA')
  })

  it('reopened carries its reason when one was given', () => {
    expect(describeHistoryEntry(entry({ action: 'REOPENED', note: 'Client reports it recurred' })))
      .toBe('Reopened — Client reports it recurred')
    expect(describeHistoryEntry(entry({ action: 'REOPENED', note: undefined }))).toBe('Reopened')
  })

  it('labels a known field name from a generic FIELD_CHANGED row', () => {
    expect(describeHistoryEntry(entry({ action: 'FIELD_CHANGED', fieldName: 'pctComplete', oldValue: '20', newValue: '40' })))
      .toBe('Percent complete changed 20 → 40')
  })

  it('falls back to the raw column name for an unrecognised field', () => {
    expect(describeHistoryEntry(entry({ action: 'FIELD_CHANGED', fieldName: 'customFieldX', newValue: 'Y' })))
      .toBe('customFieldX set to Y')
  })

  it('CREATED and CLOSED need no values at all', () => {
    expect(describeHistoryEntry(entry({ action: 'CREATED' }))).toBe('Ticket created')
    expect(describeHistoryEntry(entry({ action: 'CLOSED' }))).toBe('Ticket closed')
  })

  it('an unrecognised action still degrades to a readable sentence', () => {
    expect(describeHistoryEntry(entry({ action: 'SOMETHING_NEW', oldValue: 'A', newValue: 'B' })))
      .toBe('SOMETHING_NEW changed A → B')
  })
})
