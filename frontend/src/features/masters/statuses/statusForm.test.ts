import { describe, expect, it } from 'vitest'

import type { Status } from '@/api/generated/model/status'
import type { StatusTransition } from '@/api/generated/model/statusTransition'

import {
  CATEGORY_LABELS,
  CONTRACT_STATUS_CODES,
  GOVERNANCE_NOTES,
  emptyStatusForm,
  matrixHasOnCreateMove,
  moveKey,
  statusFormErrors,
  toFormValues,
  toMatrixRows,
  toMatrixWriteRequest,
  toPatchRequest,
  toWriteRequest,
  type MatrixRow,
  type StatusFormValues,
} from './statusForm'

/**
 * B-039 · S-13 tab 1's rules, without rendering anything.
 *
 * The two halves that earn their own tests for different reasons: the form's
 * validation duplicates a subset of the server's on purpose and has to duplicate
 * exactly that subset, and the matrix mappers are where a quiet mistake shows up
 * as a save that silently drops a permission.
 */

describe('statusFormErrors', () => {
  const valid: StatusFormValues = {
    ...emptyStatusForm,
    code: 'ON_HOLD',
    name: 'On Hold',
    category: 'IN_PROGRESS',
    colour: '#F59E0B',
  }

  it('accepts a well-formed status', () => {
    expect(statusFormErrors(valid)).toEqual({})
  })

  it('requires a name', () => {
    expect(statusFormErrors({ ...valid, name: '   ' }).name).toBeDefined()
  })

  it('bounds the name at the column width', () => {
    expect(statusFormErrors({ ...valid, name: 'x'.repeat(41) }).name).toBeDefined()
    expect(statusFormErrors({ ...valid, name: 'x'.repeat(40) }).name).toBeUndefined()
  })

  it('requires a #RRGGBB colour', () => {
    expect(statusFormErrors({ ...valid, colour: 'amber' }).colour).toBeDefined()
  })

  it('accepts a blank order and refuses a non-integer one', () => {
    expect(statusFormErrors({ ...valid, seq: '' }).seq).toBeUndefined()
    expect(statusFormErrors({ ...valid, seq: '10' }).seq).toBeUndefined()
    expect(statusFormErrors({ ...valid, seq: '1.5' }).seq).toBeDefined()
    expect(statusFormErrors({ ...valid, seq: '-1' }).seq).toBeDefined()
    expect(statusFormErrors({ ...valid, seq: '40000' }).seq).toBeDefined()
  })

  it('refuses terminal and open together', () => {
    expect(statusFormErrors({ ...valid, isTerminal: true, isOpen: true }).isTerminal)
      .toBeDefined()
    expect(statusFormErrors({ ...valid, isTerminal: true, isOpen: false }).isTerminal)
      .toBeUndefined()
  })

  /**
   * The check that is deliberately absent. `RESOLVED` is DONE work on a ticket
   * that stays open until sign-off, and it is one of the eight seeded rows — a
   * form enforcing "DONE implies not open" would refuse the row the blueprint
   * asks for.
   */
  it('does not refuse DONE while still open — RESOLVED is exactly that row', () => {
    expect(statusFormErrors({
      ...valid, code: 'RESOLVED', category: 'DONE', isOpen: true, isTerminal: false,
    })).toEqual({})
  })

  /**
   * The three the browser cannot know. Listed as an explicit non-assertion so
   * that adding one here later is a deliberate act rather than a drift.
   */
  it('leaves uniqueness and the retire block to the server', () => {
    expect(statusFormErrors({ ...valid, name: 'New' })).toEqual({})
    expect(statusFormErrors({ ...valid, isActive: false })).toEqual({})
  })
})

describe('the wire mappers', () => {
  const values: StatusFormValues = {
    code: 'ON_HOLD',
    name: '  On Hold  ',
    category: 'IN_PROGRESS',
    colour: '#F59E0B',
    seq: '30',
    isOpen: true,
    isTerminal: false,
    isActive: true,
  }

  it('trims the name and sends the numbers as numbers', () => {
    expect(toWriteRequest(values)).toMatchObject({ name: 'On Hold', seq: 30 })
  })

  it('sends a blank order as null, which the server reads as "add at the end"', () => {
    expect(toWriteRequest({ ...values, seq: '' }).seq).toBeNull()
  })

  /**
   * The code goes on every patch, and that is the point: sending the stored one
   * is a no-op, and sending it is what makes a *changed* one refusable.
   * `tickets.status` holds this string with no foreign key, so a rename would
   * orphan rather than cascade.
   */
  it('sends the code on the patch so a changed one can be refused', () => {
    expect(toPatchRequest(values).code).toBe('ON_HOLD')
  })

  it('round-trips a stored row through the form and back', () => {
    const stored: Status = {
      id: 3, code: 'ON_HOLD', name: 'On Hold', category: 'IN_PROGRESS',
      colour: '#F59E0B', seq: 30, isOpen: true, isTerminal: false, isActive: true,
      ticketCount: 0, transitionCount: 12, deactivatedTransitions: null,
    }
    expect(toPatchRequest(toFormValues(stored))).toMatchObject({
      code: 'ON_HOLD', name: 'On Hold', category: 'IN_PROGRESS', seq: 30,
      isOpen: true, isTerminal: false, isActive: true,
    })
  })
})

describe('toMatrixRows', () => {
  const statuses = [
    status(1, 'NEW', 10), status(2, 'IN_PROGRESS', 20),
    status(3, 'ON_HOLD', 30), status(7, 'CLOSED', 70),
  ]

  it('groups one row per move with a cell per role', () => {
    const rows = toMatrixRows([
      transition(1, null, 'NEW', 'ADMIN', true),
      transition(2, null, 'NEW', 'PM', true),
      transition(3, 'NEW', 'IN_PROGRESS', 'PM', true),
    ], statuses)

    expect(rows).toHaveLength(2)
    expect(Object.keys(rows[0].cells)).toEqual(['ADMIN', 'PM'])
  })

  /**
   * The distinction the read exists to preserve. A cleared cell and a
   * never-configured cell render identically, and restoring the first is a click
   * where authoring the second is a decision — dropping the inactive rows here
   * would erase that before the screen ever saw it.
   */
  it('keeps cleared cells as rows, marked, rather than dropping them', () => {
    const rows = toMatrixRows([
      transition(1, null, 'NEW', 'ADMIN', true),
      transition(2, 'NEW', 'IN_PROGRESS', 'QA', false),
    ], statuses)

    expect(rows).toHaveLength(2)
    const cleared = rows.find((r) => r.fromStatus === 'NEW')!
    expect(cleared.cells.QA).toMatchObject({ allowed: false, wasCleared: true })
  })

  it('puts the on-create rows first and orders the rest by the statuses own seq', () => {
    const rows = toMatrixRows([
      transition(1, 'ON_HOLD', 'IN_PROGRESS', 'PM', true),
      transition(2, 'NEW', 'IN_PROGRESS', 'PM', true),
      transition(3, null, 'NEW', 'ADMIN', true),
    ], statuses)

    expect(rows.map((r) => r.fromStatus)).toEqual([null, 'NEW', 'ON_HOLD'])
  })

  it('carries requiresReason and requiresEffort onto the cell', () => {
    const rows = toMatrixRows(
      [transition(1, 'IN_PROGRESS', 'CLOSED', 'PM', true, true, true)], statuses)

    expect(rows[0].cells.PM).toMatchObject({ requiresReason: true, requiresEffort: true })
  })
})

describe('toMatrixWriteRequest', () => {
  it('sends only the allowed cells — absence is how a cleared one is expressed', () => {
    const rows: MatrixRow[] = [{
      fromStatus: null,
      toStatus: 'NEW',
      cells: {
        ADMIN: { allowed: true, requiresReason: false, requiresEffort: false, wasCleared: false },
        QA: { allowed: false, requiresReason: false, requiresEffort: false, wasCleared: true },
      },
    }]

    expect(toMatrixWriteRequest(rows)).toEqual([
      { fromStatus: null, toStatus: 'NEW', roleCode: 'ADMIN', requiresReason: false, requiresEffort: false },
    ])
  })

  it('keeps a null fromStatus, which is the on-create row', () => {
    const rows: MatrixRow[] = [{
      fromStatus: null,
      toStatus: 'NEW',
      cells: {
        ADMIN: { allowed: true, requiresReason: false, requiresEffort: false, wasCleared: false },
      },
    }]

    expect(toMatrixWriteRequest(rows)[0].fromStatus).toBeNull()
  })

  it('round-trips the grid without inventing or losing a cell', () => {
    const statuses = [status(1, 'NEW', 10), status(2, 'IN_PROGRESS', 20)]
    const wire = [
      transition(1, null, 'NEW', 'ADMIN', true),
      transition(2, 'NEW', 'IN_PROGRESS', 'PM', true, true, false),
    ]

    expect(toMatrixWriteRequest(toMatrixRows(wire, statuses))).toEqual([
      { fromStatus: null, toStatus: 'NEW', roleCode: 'ADMIN', requiresReason: false, requiresEffort: false },
      { fromStatus: 'NEW', toStatus: 'IN_PROGRESS', roleCode: 'PM', requiresReason: true, requiresEffort: false },
    ])
  })
})

describe('matrixHasOnCreateMove', () => {
  it('is false when every on-create cell has been unticked', () => {
    expect(matrixHasOnCreateMove([{
      fromStatus: null,
      toStatus: 'NEW',
      cells: {
        ADMIN: { allowed: false, requiresReason: false, requiresEffort: false, wasCleared: false },
      },
    }])).toBe(false)
  })

  it('is true when one survives', () => {
    expect(matrixHasOnCreateMove([{
      fromStatus: null,
      toStatus: 'NEW',
      cells: {
        ADMIN: { allowed: true, requiresReason: false, requiresEffort: false, wasCleared: false },
        PM: { allowed: false, requiresReason: false, requiresEffort: false, wasCleared: false },
      },
    }])).toBe(true)
  })

  it('ignores allowed cells on moves that are not on-create', () => {
    expect(matrixHasOnCreateMove([{
      fromStatus: 'NEW',
      toStatus: 'IN_PROGRESS',
      cells: {
        ADMIN: { allowed: true, requiresReason: false, requiresEffort: false, wasCleared: false },
      },
    }])).toBe(false)
  })
})

describe('the constants the screen and the server both depend on', () => {
  it('lists exactly the eight codes the contract enum carries', () => {
    expect([...CONTRACT_STATUS_CODES].sort()).toEqual([
      'AWAITING_INFO', 'CLOSED', 'IN_PROGRESS', 'NEW',
      'REOPENED', 'RESOLVED', 'REWORK', 'ON_HOLD',
    ].sort())
  })

  it('labels the categories in §7.4s own words', () => {
    expect(CATEGORY_LABELS).toEqual({
      TODO: 'To-do', IN_PROGRESS: 'In progress', DONE: 'Done',
    })
  })

  /**
   * The notes are keyed by `moveKey`, so a typo in either would silently show no
   * flag at all — which is the failure mode of advice: nobody notices its
   * absence.
   */
  it('keys the governance notes the way the grid looks them up', () => {
    expect(GOVERNANCE_NOTES[moveKey('RESOLVED', 'CLOSED')]).toContain('G-3')
    expect(GOVERNANCE_NOTES[moveKey('CLOSED', 'REOPENED')]).toContain('§2')
    expect(GOVERNANCE_NOTES[moveKey('IN_PROGRESS', 'RESOLVED')]).toContain('G-1')
  })

  it('distinguishes the on-create key from a move out of an empty-named status', () => {
    expect(moveKey(null, 'NEW')).not.toBe(moveKey('NEW', 'NEW'))
  })
})

// ---------------------------------------------------------------------------

function status(id: number, code: Status['code'], seq: number): Status {
  return {
    id, code, name: String(code), category: 'TODO', colour: '#4F46E5', seq,
    isOpen: true, isTerminal: false, isActive: true,
    ticketCount: 0, transitionCount: 0, deactivatedTransitions: null,
  }
}

function transition(
  id: number,
  fromStatus: StatusTransition['fromStatus'],
  toStatus: NonNullable<StatusTransition['toStatus']>,
  roleCode: NonNullable<StatusTransition['roleCode']>,
  isActive: boolean,
  requiresReason = false,
  requiresEffort = false,
): StatusTransition {
  return { id, fromStatus, toStatus, roleCode, requiresReason, requiresEffort, isActive }
}
