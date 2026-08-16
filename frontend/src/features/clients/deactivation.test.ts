import { describe, expect, it } from 'vitest'

import { deactivationWarning } from './deactivation'

/**
 * B-029 · when S-33's Save is a deactivation worth warning about.
 *
 * The four silent cases are each a decision rather than a fall-through, so each
 * gets an assertion — a helper that warned on all four would still pass a test
 * that only covered the positive case.
 */
const ACME = { id: 1, name: 'Acme Retail Ltd', clientCode: 'ACME', openTicketCount: 7 }

describe('deactivationWarning', () => {
  it('warns when an active client with open tickets is being deactivated', () => {
    expect(deactivationWarning({ ...ACME, status: 'ACTIVE' }, 'INACTIVE')).toEqual({
      id: 1,
      name: 'Acme Retail Ltd',
      clientCode: 'ACME',
      openTicketCount: 7,
    })
  })

  /**
   * A prospect is active by `ClientStatus.isActive` (`<> INACTIVE`), so closing
   * one is a deactivation like any other and its open tickets are real.
   */
  it('warns when a prospect with open tickets is being deactivated', () => {
    expect(deactivationWarning({ ...ACME, status: 'PROSPECT' }, 'INACTIVE')).not.toBeNull()
  })

  it('is silent on a create — there is no client to have tickets yet', () => {
    expect(deactivationWarning(null, 'INACTIVE')).toBeNull()
  })

  /** Active ⇄ Prospect is a reclassification. Only INACTIVE blocks tickets. */
  it('is silent when the next status is not INACTIVE', () => {
    expect(deactivationWarning({ ...ACME, status: 'ACTIVE' }, 'PROSPECT')).toBeNull()
    expect(deactivationWarning({ ...ACME, status: 'PROSPECT' }, 'ACTIVE')).toBeNull()
  })

  /**
   * Editing the address of a client that is already closed. Warning about a
   * state that is not changing is how a confirmation becomes something people
   * click through without reading.
   */
  it('is silent when the client is already inactive', () => {
    expect(deactivationWarning({ ...ACME, status: 'INACTIVE' }, 'INACTIVE')).toBeNull()
  })

  /** The same call the bulk bar makes — nothing outstanding, nothing to say. */
  it('is silent when there are no open tickets', () => {
    expect(
      deactivationWarning({ ...ACME, status: 'ACTIVE', openTicketCount: 0 }, 'INACTIVE'),
    ).toBeNull()
    expect(
      deactivationWarning(
        { id: 1, name: 'Acme', clientCode: 'ACME', status: 'ACTIVE' },
        'INACTIVE',
      ),
    ).toBeNull()
  })
})
