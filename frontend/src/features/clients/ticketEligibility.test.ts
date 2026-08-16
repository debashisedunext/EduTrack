import { describe, expect, it } from 'vitest'

import { canRaiseTicket, newTicketBlockReason } from './ticketEligibility'

/**
 * B-029 · the corpus for both new-ticket gates.
 *
 * The cases that matter are the ones where the two gates interact and the ones
 * where a field is absent — an eligible client and a plainly inactive one would
 * pass against almost any implementation.
 */
describe('newTicketBlockReason', () => {
  it('allows an active client with a primary contact', () => {
    expect(newTicketBlockReason({ isActive: true, hasPrimaryContact: true })).toBeNull()
    expect(canRaiseTicket({ isActive: true, hasPrimaryContact: true })).toBe(true)
  })

  it('blocks a deactivated client, naming deactivation', () => {
    expect(newTicketBlockReason({ isActive: false, hasPrimaryContact: true })).toBe(
      'Inactive — new tickets are blocked',
    )
  })

  it('blocks an active client with no primary contact', () => {
    expect(newTicketBlockReason({ isActive: true, hasPrimaryContact: false })).toBe(
      'No primary contact',
    )
  })

  /**
   * A prospect reaches this as `isActive: true` — the wire's boolean is
   * `status <> 'INACTIVE'` (B-026), which B-028 corrected the server filter to
   * agree with. If that ever narrows back to `= ACTIVE`, this is the assertion
   * that says raising a pre-sales ticket stopped being possible.
   */
  it('allows a prospect, which arrives as active', () => {
    expect(newTicketBlockReason({ isActive: true, hasPrimaryContact: true })).toBeNull()
  })

  it('reports deactivation first when both gates fail', () => {
    expect(newTicketBlockReason({ isActive: false, hasPrimaryContact: false })).toBe(
      'Inactive — new tickets are blocked',
    )
  })

  /**
   * The half B-028 argued at length on the contract: `primaryContact` is
   * omitted rather than nulled when there is none, so anything permissive about
   * absence offers every client. Both fields fail closed.
   */
  it('blocks when either field is absent rather than assuming permission', () => {
    expect(newTicketBlockReason({})).toBe('Inactive — new tickets are blocked')
    expect(newTicketBlockReason({ isActive: true })).toBe('No primary contact')
    expect(canRaiseTicket({ hasPrimaryContact: true })).toBe(false)
  })
})
