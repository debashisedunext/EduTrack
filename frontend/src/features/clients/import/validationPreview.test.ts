import { describe, expect, it } from 'vitest'

import type { ImportPreviewResponseData, ImportRowVerdict } from '@/api/generated/model'

import {
  VERDICTS,
  VERDICT_PRESENTATION,
  countFor,
  filterRows,
  hasWritableRows,
  naturalKeyValue,
  summaryLine,
  writableCount,
} from './validationPreview'

/**
 * B-034 · step 4's rules, as functions.
 *
 * The one that matters most is `writableCount`. It is the number step 5's
 * "import valid rows only" is about, it is not any of the four the summary shows,
 * and the natural mistake — reaching for `willCreate` — silently omits every
 * update. A file of 400 updates would offer to import zero rows.
 */

function preview(overrides: Partial<ImportPreviewResponseData> = {}): ImportPreviewResponseData {
  return { willCreate: 0, willUpdate: 0, duplicates: 0, rejected: 0, rows: [], ...overrides }
}

function row(
  rowNumber: number,
  verdict: ImportRowVerdict['verdict'],
  values: Record<string, string> = {},
): ImportRowVerdict {
  return { rowNumber, verdict, reason: null, values }
}

describe('the summary', () => {
  /** Blueprint §4B.3's own line, word for word. */
  it('reads the way the blueprint writes it', () => {
    expect(summaryLine(preview({ willCreate: 412, willUpdate: 38, rejected: 6, duplicates: 2 })))
      .toBe('412 create · 38 update · 6 rejected · 2 duplicates')
  })

  /**
   * A clean file still says "0 rejected". Entries that appear and disappear make
   * two runs of the same import hard to compare at a glance, and a zero is
   * information.
   */
  it('keeps every verdict at zero rather than dropping it', () => {
    expect(summaryLine(preview({ willCreate: 5 })))
      .toBe('5 create · 0 update · 0 rejected · 0 duplicates')
  })

  it('reads each count off the field the server derived it into', () => {
    const counts = preview({ willCreate: 1, willUpdate: 2, rejected: 3, duplicates: 4 })

    expect(VERDICTS.map((verdict) => countFor(counts, verdict))).toEqual([1, 2, 3, 4])
  })
})

describe('what a commit would write', () => {
  /**
   * Updates count. This is the assertion the whole file exists for: `willCreate`
   * is the number somebody reaches for, and on a file of corrections it is zero
   * while 400 rows are about to be written.
   */
  it('counts creates and updates, not creates alone', () => {
    expect(writableCount(preview({ willCreate: 412, willUpdate: 38 }))).toBe(450)
  })

  it('excludes duplicates and rejections', () => {
    expect(writableCount(preview({ willCreate: 1, rejected: 99, duplicates: 99 }))).toBe(1)
  })

  it('is nothing when every row is a problem', () => {
    expect(hasWritableRows(preview({ rejected: 6, duplicates: 2 }))).toBe(false)
  })

  /** A file of pure updates is importable, and the button must not say otherwise. */
  it('is something when every row is an update', () => {
    expect(hasWritableRows(preview({ willUpdate: 38 }))).toBe(true)
  })
})

describe('filtering', () => {
  const rows = [
    row(2, 'WILL_CREATE'),
    row(3, 'WILL_UPDATE'),
    row(4, 'REJECTED'),
    row(5, 'WILL_CREATE'),
  ]

  it('shows every row by default, in the file’s order', () => {
    expect(filterRows(rows, 'all').map((r) => r.rowNumber)).toEqual([2, 3, 4, 5])
  })

  it('narrows to one verdict and keeps the file’s order within it', () => {
    expect(filterRows(rows, 'WILL_CREATE').map((r) => r.rowNumber)).toEqual([2, 5])
  })

  it('answers empty for a verdict nothing has', () => {
    expect(filterRows(rows, 'DUPLICATE_IN_FILE')).toEqual([])
  })
})

describe('the natural-key column', () => {
  it('reads the field the schema says rows are matched on', () => {
    expect(naturalKeyValue(row(2, 'WILL_CREATE', { clientCode: 'ACME' }), 'clientCode'))
      .toBe('ACME')
  })

  /**
   * Blueprint §4B.3's row 5 — rejected *because* the code is blank, so the cell
   * has nothing to show. Null rather than an empty string, so the screen renders
   * "(blank)" instead of a gap that reads as a rendering fault at exactly the
   * moment the user is looking for a reason.
   */
  it('is null when the row has no key, which is why the row was rejected', () => {
    expect(naturalKeyValue(row(5, 'REJECTED', { name: 'No Code Here' }), 'clientCode'))
      .toBeNull()
  })

  /** B-038's resource import shows an employee code in the same column. */
  it('is not tied to clients', () => {
    expect(naturalKeyValue(row(2, 'WILL_CREATE', { employeeCode: 'E-1' }), 'employeeCode'))
      .toBe('E-1')
  })
})

describe('how a verdict is spoken about', () => {
  /**
   * "Duplicate in file" is deliberately not a rejection. Nothing is wrong with
   * the row's content, and telling somebody their perfectly valid row was
   * rejected sends them looking for a fault that is not there.
   */
  it('distinguishes a duplicate from a rejection in words, not only in colour', () => {
    expect(VERDICT_PRESENTATION.DUPLICATE_IN_FILE.label).toBe('Duplicate in file')
    expect(VERDICT_PRESENTATION.DUPLICATE_IN_FILE.meaning).not.toMatch(/rejected/i)
  })

  /** Every one has a meaning, because a badge is a word without a consequence. */
  it('gives every verdict something to read out after the label', () => {
    for (const verdict of VERDICTS) {
      expect(VERDICT_PRESENTATION[verdict].meaning.length).toBeGreaterThan(0)
    }
  })

  /** §12.1: no colour that is not a token, so these are token names. */
  it('names tones rather than colours', () => {
    expect(VERDICTS.map((verdict) => VERDICT_PRESENTATION[verdict].tone))
      .toEqual(['success', 'info', 'danger', 'warning'])
  })
})
