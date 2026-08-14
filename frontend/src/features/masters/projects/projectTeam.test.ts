import { describe, expect, it } from 'vitest'

import type { ProjectMember } from '@/api/generated/model/projectMember'

import {
  allocationChangePatch,
  isRemovable,
  NO_PROJECT_ROLE,
  overridesGlobalRole,
  roleChangePatch,
  summariseAllocation,
} from './projectTeam'

/**
 * B-017 · the Team tab's two decisions, tested where they are made.
 *
 * The interesting one is **clear versus omit**. The `PATCH` reads an omitted key
 * as "leave it alone" and an explicit null as "clear it", so a helper that
 * dropped the key would make "same as their global role" and "not stated"
 * write-once — settable when the member is added and unreachable afterwards.
 * Nothing in a rendered page would show that: the row would just go on
 * displaying the old value, which looks like a stale cache.
 */

const member = (over: Partial<ProjectMember> = {}): ProjectMember => ({
  userId: 3,
  displayName: 'Ravi Kumar',
  email: 'ravi@edunext.example',
  role: 'DEVELOPER',
  projectRole: null,
  allocationPct: null,
  isActive: true,
  openTicketCount: 0,
  addedAt: '2026-01-05T09:00:00.000Z',
  ...over,
})

describe('roleChangePatch', () => {
  it('sends an explicit null when the role is cleared, never an empty patch', () => {
    // `{}` would be "leave it alone", so the member would keep the role the
    // admin just removed and the select would snap back on the next refetch.
    expect(roleChangePatch(NO_PROJECT_ROLE)).toEqual({ projectRole: null })
  })

  it('sends the code when a role is chosen', () => {
    expect(roleChangePatch('QA')).toEqual({ projectRole: 'QA' })
  })

  it('never sends the sentinel itself', () => {
    // INHERIT is deliberately not a ProjectRoleCode, so if it ever leaked onto
    // the wire the server's @Pattern refuses it loudly rather than storing a
    // seventh role nobody defined.
    expect(roleChangePatch(NO_PROJECT_ROLE).projectRole).not.toBe(NO_PROJECT_ROLE)
  })
})

describe('allocationChangePatch', () => {
  it('clears with an explicit null when the box is emptied', () => {
    // "Not stated" is a real value. Omitting the key would make an allocation
    // entered by mistake permanent.
    expect(allocationChangePatch('')).toEqual({ allocationPct: null })
    expect(allocationChangePatch('   ')).toEqual({ allocationPct: null })
  })

  it('keeps zero as a value, not an absence', () => {
    // "No capacity committed" and "not stated" are different facts, and the
    // whole nullable column exists to tell them apart.
    expect(allocationChangePatch('0')).toEqual({ allocationPct: 0 })
  })

  it('accepts the whole range', () => {
    expect(allocationChangePatch('100')).toEqual({ allocationPct: 100 })
    expect(allocationChangePatch(' 40 ')).toEqual({ allocationPct: 40 })
  })

  it('refuses to send anything out of range or fractional', () => {
    // Null here means "do not send", which the caller shows as a message. It is
    // deliberately not the same as `{ allocationPct: null }`, which means clear.
    expect(allocationChangePatch('101')).toBeNull()
    expect(allocationChangePatch('-1')).toBeNull()
    expect(allocationChangePatch('40.5')).toBeNull()
    expect(allocationChangePatch('half')).toBeNull()
  })
})

describe('summariseAllocation', () => {
  it('counts only stated allocations, and reports the rest separately', () => {
    // The claim the whole nullable column rests on: a member with no allocation
    // contributes nothing rather than 100. Folding them in would have made
    // almost every real project read as wildly over-committed on day one.
    const summary = summariseAllocation([
      member({ userId: 1, allocationPct: 60 }),
      member({ userId: 2, allocationPct: 0 }),
      member({ userId: 3, allocationPct: null }),
    ])

    expect(summary.totalPct).toBe(60)
    expect(summary.statedCount).toBe(2)
    expect(summary.unstatedCount).toBe(1)
  })

  it('says nothing is stated rather than zero percent, when nothing is', () => {
    const summary = summariseAllocation([member(), member({ userId: 9 })])

    expect(summary.statedCount).toBe(0)
    expect(summary.unstatedCount).toBe(2)
  })
})

describe('isRemovable', () => {
  it('blocks a member holding open tickets on this project', () => {
    expect(isRemovable(member({ openTicketCount: 2 }))).toBe(false)
  })

  it('allows one holding none', () => {
    expect(isRemovable(member({ openTicketCount: 0 }))).toBe(true)
    // A roster row that predates the count still has to be removable rather
    // than permanently stuck.
    expect(isRemovable(member({ openTicketCount: undefined }))).toBe(true)
  })
})

describe('overridesGlobalRole', () => {
  it('is true only when the two actually differ', () => {
    expect(overridesGlobalRole(member({ role: 'DEVELOPER', projectRole: 'QA' }))).toBe(true)
  })

  it('is false when no project role is set — that is not an override', () => {
    // "Same as their global role" is the common case and would drown the signal
    // if it were chipped.
    expect(overridesGlobalRole(member({ role: 'DEVELOPER', projectRole: null }))).toBe(false)
  })

  it('is false when the project role restates the global one', () => {
    expect(overridesGlobalRole(member({ role: 'QA', projectRole: 'QA' }))).toBe(false)
  })
})
