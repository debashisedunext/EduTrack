import { describe, expect, it } from 'vitest'

import { ApiError } from '@/api/http'
import type { ImportBatch, ImportReversalResponseData } from '@/api/generated/model'

import {
  formatRunTime,
  reversalOutcome,
  reversalRefusal,
  reversalWarning,
  reverseDisabledReason,
} from './importHistory'

/**
 * B-037 · the sentences shown to somebody about to delete four hundred clients.
 *
 * These are pure functions on purpose — `ImportHistoryPanel.test.tsx` renders
 * the table, and a component test that has to mount a grid to assert on a string
 * ends up testing the grid. Everything a user reads before pressing Reverse, and
 * everything they read afterwards, is decided here.
 */

const BATCH: ImportBatch = {
  batchId: 412,
  entity: 'CLIENT',
  fileName: 'clients-august.xlsx',
  status: 'COMPLETED',
  processed: 31,
  total: 31,
  created: 24,
  updated: 4,
  rejected: 3,
  errorReportUrl: '/import-batches/412/error-report',
  startedAt: '2026-08-17T11:48:00.000Z',
  importedBy: 1,
  importedByName: 'Anita Desai',
  reversedAt: null,
  reversedRows: 0,
  retainedRows: 0,
  reversible: true,
}

function batch(overrides: Partial<ImportBatch> = {}): ImportBatch {
  return { ...BATCH, ...overrides }
}

/** Same shape `commitProgress.test.ts` uses, so the two read alike. */
function problem(type: string, detail: string, status = 422): ApiError {
  return new ApiError(
    status,
    {
      type: `https://edutrack/errors/${type}`,
      title: 'Refused',
      status,
      detail,
    },
    new Response(null, { status }),
  )
}

describe('the warning shown before a reversal', () => {
  it('says how many clients will be deleted', () => {
    expect(reversalWarning(batch({ created: 24 })).deletes).toContain('24 clients')
  })

  it('does not say "1 clients"', () => {
    expect(reversalWarning(batch({ created: 1 })).deletes).toContain('the 1 client this import created')
  })

  /**
   * **The sentence this whole dialog exists for.** A reversal cannot restore what
   * the run updated — the batch id is stamped on insert only, and there is no
   * before image. An Admin pressing a button labelled Reverse reasonably expects
   * all 412 rows to go back to how they were, and saying so afterwards would be
   * too late: they would have pressed it believing something else.
   */
  it('warns that updated clients are not restored, before anything is deleted', () => {
    const warning = reversalWarning(batch({ created: 12, updated: 400 }))

    expect(warning.keeps).toContain('400 clients it updated are not restored')
    expect(warning.keeps).toContain('not kept anywhere')
  })

  it('omits that warning entirely when the run updated nothing', () => {
    expect(reversalWarning(batch({ created: 24, updated: 0 })).keeps).toBeNull()
  })
})

describe('the result shown after a reversal', () => {
  function result(overrides: Partial<ImportReversalResponseData> = {}): ImportReversalResponseData {
    return {
      batch: batch({ reversedAt: '2026-08-18T09:02:00Z', reversedRows: 24, reversible: false }),
      deleted: [],
      retained: [],
      updatedRowsNotReverted: 0,
      ...overrides,
    }
  }

  it('reports what was deleted from the batch, not from the request', () => {
    expect(reversalOutcome(result()).headline).toContain('24 clients')
  })

  it('says plainly when a run had created nothing', () => {
    expect(
      reversalOutcome(
        result({ batch: batch({ reversedRows: 0, reversedAt: '2026-08-18T09:02:00Z' }) }),
      ).headline,
    ).toContain('had not created any clients')
  })

  /**
   * A retained client is an outcome, not a failure — the alternatives were
   * failing the whole reversal because one client got used, or destroying a
   * ticket's client.
   */
  it('explains retained clients without calling the reversal a failure', () => {
    const { retained } = reversalOutcome(
      result({
        retained: [{ naturalKey: 'ZENITH', reason: 'Kept — 3 tickets.' }],
      }),
    )

    expect(retained).toContain('1 client was kept')
    expect(retained).toContain('work has been raised against it')
    expect(retained).not.toMatch(/fail/i)
  })

  it('says nothing about retained clients when there are none', () => {
    expect(reversalOutcome(result()).retained).toBeNull()
  })

  /** The number that accounts for the rows the user is otherwise left wondering about. */
  it('accounts for the rows the run updated and the reversal did not touch', () => {
    const { notReverted } = reversalOutcome(result({ updatedRowsNotReverted: 400 }))

    expect(notReverted).toContain('400 clients')
    expect(notReverted).toContain('cannot be put back')
  })
})

describe('why the Reverse button is disabled', () => {
  /**
   * The flag is the server's answer and this only explains it. Re-deriving the
   * *enabled* state here would be a second copy of `ImportReversalService`'s
   * refusals, on a screen whose job is deleting rows.
   */
  it('says nothing for a batch the server calls reversible', () => {
    expect(reverseDisabledReason(batch({ reversible: true }))).toBeNull()
  })

  it('explains a batch that has already been reversed', () => {
    expect(
      reverseDisabledReason(
        batch({ reversible: false, reversedAt: '2026-08-18T09:02:00Z' }),
      ),
    ).toContain('already been reversed')
  })

  it('explains a run that has not finished', () => {
    expect(reverseDisabledReason(batch({ reversible: false, status: 'RUNNING' })))
      .toContain('not finished')
  })

  /**
   * A rule added on the server that this file does not know about must leave the
   * button correctly disabled with a vaguer tooltip, never incorrectly enabled
   * with a confident one.
   */
  it('falls back to a vague reason rather than re-enabling itself', () => {
    expect(reverseDisabledReason(batch({ reversible: false, status: 'COMPLETED' })))
      .toBe('This import cannot be reversed.')
  })
})

describe('the refusal a reversal can come back with', () => {
  /**
   * Three types, three remedies. One shared "cannot reverse" would put a Try
   * again on two cases that will refuse for ever.
   */
  it('offers to wait for a run that is still going', () => {
    expect(reversalRefusal(problem('import-batch-not-finished', 'Import #412 is still running.')))
      .toEqual({ message: 'Import #412 is still running.', remedy: 'wait' })
  })

  it('offers a refresh for a batch someone else has already reversed', () => {
    expect(
      reversalRefusal(
        problem('import-batch-already-reversed', 'Import #412 has already been reversed.'),
      ).remedy,
    ).toBe('refresh')
  })

  it('does not offer a retry when the importer is not installed', () => {
    expect(
      reversalRefusal(problem('import-schema-unavailable', 'The GADGET importer is missing.'))
        .remedy,
    ).toBe('contact')
  })

  it('names the permission rather than the status code on a 403', () => {
    expect(reversalRefusal(problem('forbidden', 'no', 403)).message)
      .toContain('administrator action')
  })

  it('falls back to something actionable when the failure is not an ApiError', () => {
    expect(reversalRefusal(new TypeError('offline')))
      .toEqual({
        message: 'The import could not be reversed. Check your connection and try again.',
        remedy: 'retry',
      })
  })
})

describe('when a run happened', () => {
  it('renders an instant a person can read', () => {
    expect(formatRunTime('2026-08-17T11:48:00.000Z')).toMatch(/2026/)
  })

  /** A missing or malformed timestamp renders a dash, never `Invalid Date`. */
  it('renders a dash rather than Invalid Date', () => {
    expect(formatRunTime(undefined)).toBe('—')
    expect(formatRunTime('not a date')).toBe('—')
  })
})
