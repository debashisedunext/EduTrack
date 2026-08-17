import { describe, expect, it } from 'vitest'

import type { Cycle } from '@/api/generated/model/cycle'
import type { Ticket } from '@/api/generated/model/ticket'

import { canChangeLevel, isLevelReasonRequired, slaClockStart } from './levelChange'

/**
 * C-020 · §4B.1's two rules and the one date, as pure functions.
 *
 * These are the three places the panel can be wrong without anything looking
 * wrong, which is why they are extracted rather than inlined into the control:
 * a role check that lets a Developer through, a reason rule that lets an
 * assigned ticket be releveled silently, and a clock start that makes a breached
 * ticket read as on track.
 */

describe('canChangeLevel — §4B.1, "Admin, PM, Support Desk"', () => {
  it.each(['ADMIN', 'PM', 'SUPPORT'] as const)('allows %s', (role) => {
    expect(canChangeLevel(role)).toBe(true)
  })

  /**
   * The half that matters. §4B.1 gives these three a *request* path instead, and
   * it is a notification to the PM rather than a write — so a control drawn here
   * would be one the server refuses with 403.
   */
  it.each(['DEVELOPER', 'QA', 'DEPLOYMENT'] as const)('refuses %s', (role) => {
    expect(canChangeLevel(role)).toBe(false)
  })

  /**
   * `useGetMe()` has not resolved. False is deliberate — the row renders as the
   * plain chip until the viewer is known, so nothing shifts sideways under the
   * cursor a beat after the panel has been read. C-017 learned this on S-17's
   * selection column.
   */
  it('refuses an unknown role rather than assuming', () => {
    expect(canChangeLevel(undefined)).toBe(false)
  })
})

describe('isLevelReasonRequired — §4B.1, "mandatory when the ticket is already assigned"', () => {
  it('is required once somebody holds the ticket', () => {
    expect(isLevelReasonRequired({ assignee: { id: 44, displayName: 'Meera Iyer' } })).toBe(true)
  })

  /** "Optional at creation" — a ticket in triage is having its level *set*. */
  it('is optional while the ticket is unassigned', () => {
    expect(isLevelReasonRequired({ assignee: undefined })).toBe(false)
  })
})

describe('slaClockStart — where the preview measures from', () => {
  const ticket = (cycleNo: number, createdAt?: string) =>
    ({ cycleNo, createdAt }) as Pick<Ticket, 'createdAt' | 'cycleNo'>

  const cycle = (cycleNo: number, startedAt: string) => ({ cycleNo, startedAt }) as Cycle

  it('is the current cycle’s start date', () => {
    expect(
      slaClockStart(ticket(1, '2026-08-01T08:00:00Z'), [cycle(1, '2026-08-03T09:00:00Z')]),
    ).toBe('2026-08-03T09:00:00Z')
  })

  /**
   * The reopen case, and the reason this is not simply `createdAt`. Cycle 2's
   * SLA has never had anything to do with when the ticket was first raised, so
   * measuring from creation would show a date weeks in the past for a cycle that
   * opened this morning.
   */
  it('prefers the current cycle after a reopen, not cycle 1 and not creation', () => {
    expect(
      slaClockStart(ticket(2, '2026-08-01T08:00:00Z'), [
        cycle(1, '2026-08-03T09:00:00Z'),
        cycle(2, '2026-08-14T11:00:00Z'),
      ]),
    ).toBe('2026-08-14T11:00:00Z')
  })

  /** `PriorityChangeService.slaClockStart` falls back the same way. */
  it('falls back to when the ticket was created', () => {
    expect(slaClockStart(ticket(1, '2026-08-01T08:00:00Z'), undefined)).toBe('2026-08-01T08:00:00Z')
  })

  /**
   * Undefined rather than a guess. `usePlannedCloseDate` then omits `from` and
   * the server measures from now, which is wrong by less than any date this
   * function could invent.
   */
  it('is undefined when neither is known', () => {
    expect(slaClockStart(ticket(1, undefined), [])).toBeUndefined()
  })
})
