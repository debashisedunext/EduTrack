import { describe, expect, it } from 'vitest'

import type { ObProjectDetail } from '@/api/generated/model/obProjectDetail'

import {
  projectEditRequestOf,
  projectEditValuesOf,
  statusNeedsReason,
  validateProjectEdit,
  type ProjectEditValues,
} from './editProjectForm'

/**
 * The Edit project dialog's rules, apart from the dialog.
 *
 * <p>The one that matters most is the last: the request is the whole
 * representation, and a cleared person is sent as `null`, never left out.
 * The contract's own words are that an absent implementor means *cleared* —
 * so this is what separates "unassigned on purpose" from "the form forgot".
 */

function project(over: Partial<ObProjectDetail> = {}): ObProjectDetail {
  return {
    id: 7,
    name: 'DAV Proj',
    client: { id: 3, name: 'DAV School', clientCode: 'DAV-101', city: null },
    product: { id: 1, code: 'EDUNEXT_ERP', name: 'EDUNEXT-ERP' },
    startDate: '2026-09-15',
    salesPerson: { id: 12, displayName: 'Aditya Rawat' },
    implementor: { id: 26, displayName: 'Kavya Sharma' },
    implementorManager: { id: 31, displayName: 'Rahul Menon' },
    status: 'RUNNING',
    gateStatus: 'OPEN',
    stagesComplete: 0,
    stagesTotal: 7,
    journeyCount: 2,
    totalTatDays: 8,
    stages: [],
    moduleServices: [],
    createdAt: '2026-09-15T00:00:00Z',
    ...over,
  } as ObProjectDetail
}

function values(over: Partial<ProjectEditValues> = {}): ProjectEditValues {
  return {
    name: 'DAV Proj',
    startDate: '2026-09-15',
    salesPersonId: 12,
    implementorUserId: 26,
    implementorManagerUserId: 31,
    status: 'RUNNING',
    statusReason: '',
    ...over,
  }
}

describe('projectEditValuesOf', () => {
  it('starts from what the project says today', () => {
    expect(projectEditValuesOf(project())).toEqual(values())
  })

  it('reads an unassigned person as null, not as a missing field', () => {
    const seeded = projectEditValuesOf(
      project({ salesPerson: null, implementor: null, implementorManager: null }),
    )

    expect(seeded.salesPersonId).toBeNull()
    expect(seeded.implementorUserId).toBeNull()
    expect(seeded.implementorManagerUserId).toBeNull()
  })

  it('carries the existing reason onto a held project', () => {
    const seeded = projectEditValuesOf(
      project({ status: 'ON_HOLD', statusReason: 'Budget review' }),
    )

    expect(seeded.status).toBe('ON_HOLD')
    expect(seeded.statusReason).toBe('Budget review')
  })

  /** Completed is earned, not set, so it is not a value the form can hold. */
  it('leaves status unset on a completed project', () => {
    expect(projectEditValuesOf(project({ status: 'COMPLETED' })).status).toBeNull()
  })
})

describe('validateProjectEdit', () => {
  it('accepts a complete running project', () => {
    expect(validateProjectEdit(values())).toEqual({})
  })

  it('requires a name and a start date', () => {
    const found = validateProjectEdit(values({ name: '   ', startDate: '' }))

    expect(found.name).toBeTruthy()
    expect(found.startDate).toBeTruthy()
  })

  /** The server insists (`ObProjectStatus.requiresReason`); asking here saves a round trip. */
  it.each(['ON_HOLD', 'DROPPED'] as const)('requires a reason to mark a project %s', (status) => {
    expect(validateProjectEdit(values({ status, statusReason: ' ' })).statusReason).toBeTruthy()
    expect(validateProjectEdit(values({ status, statusReason: 'Client asked' })).statusReason)
      .toBeUndefined()
  })

  it('needs no reason to keep a project running', () => {
    expect(validateProjectEdit(values({ status: 'RUNNING', statusReason: '' }))).toEqual({})
  })

  /** Both people are nullable on the update — unassigning is a real thing to do. */
  it('allows both people to be cleared', () => {
    expect(validateProjectEdit(values({ salesPersonId: null, implementorUserId: null }))).toEqual({})
  })
})

describe('statusNeedsReason', () => {
  it('is true for the two stopped states and nothing else', () => {
    expect(statusNeedsReason('ON_HOLD')).toBe(true)
    expect(statusNeedsReason('DROPPED')).toBe(true)
    expect(statusNeedsReason('RUNNING')).toBe(false)
    expect(statusNeedsReason(null)).toBe(false)
  })
})

describe('projectEditRequestOf', () => {
  it('sends the whole representation, trimmed', () => {
    expect(projectEditRequestOf(values({ name: '  DAV Proj  ' }))).toEqual({
      name: 'DAV Proj',
      startDate: '2026-09-15',
      salesPersonId: 12,
      implementorUserId: 26,
      implementorManagerUserId: 31,
      status: 'RUNNING',
      statusReason: null,
    })
  })

  /**
   * The contract's own rule: an absent `implementorUserId` means cleared. So
   * the builder never leaves the key out — it sends `null`, which is the
   * difference between unassigning on purpose and the server reading a gap.
   */
  it('sends a cleared person as null rather than omitting the key', () => {
    const request = projectEditRequestOf(
      values({ salesPersonId: null, implementorUserId: null, implementorManagerUserId: null }),
    )

    expect(request).toHaveProperty('salesPersonId', null)
    expect(request).toHaveProperty('implementorUserId', null)
    expect(request).toHaveProperty('implementorManagerUserId', null)
  })

  it('sends the reason only with a status that needs one', () => {
    expect(projectEditRequestOf(values({ status: 'ON_HOLD', statusReason: ' Budget ' })))
      .toMatchObject({ status: 'ON_HOLD', statusReason: 'Budget' })
    expect(projectEditRequestOf(values({ status: 'RUNNING', statusReason: 'stale text' })))
      .toMatchObject({ status: 'RUNNING', statusReason: null })
  })

  /**
   * Echoing `COMPLETED` back would be refused with a 422, which would make a
   * finished project's name un-editable for no reason anybody could see.
   */
  it('sends no status at all for a completed project', () => {
    const request = projectEditRequestOf(values({ status: null }))

    expect(request).not.toHaveProperty('status')
    expect(request).not.toHaveProperty('statusReason')
  })
})
