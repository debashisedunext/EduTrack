import { describe, expect, it } from 'vitest'

import { formatLastTicketDate } from './columns'
import { EMPTY_CLIENT_FILTERS, toQueryParams } from './useClientFilters'

/**
 * B-025 · the two pieces of S-32 that are decisions rather than rendering.
 *
 * `ClientListPage.test.tsx` exercises them through the screen; these pin the
 * edges that are awkward to reach from a grid — the tri-state filter's third
 * state, and the difference between "never" and "unparseable".
 */

describe('toQueryParams', () => {
  /**
   * The tri-state's third state must be *absent*, not `false`.
   *
   * Sending `isActive=false` for "show me everyone" would hide every active
   * client — the opposite of what the empty filter means. Sending `q=''` would
   * be a free-text search for the empty string.
   */
  it('omits every unset filter rather than sending a falsy one', () => {
    expect(toQueryParams(EMPTY_CLIENT_FILTERS)).toEqual({
      q: undefined,
      isActive: undefined,
      projectId: undefined,
      supportPlan: undefined,
      accountManagerId: undefined,
    })
  })

  /** `false` is a real filter — "show me the deactivated ones" — and survives. */
  it('sends isActive=false when that is what was asked for', () => {
    expect(toQueryParams({ ...EMPTY_CLIENT_FILTERS, isActive: false }).isActive).toBe(false)
  })

  it('passes every set filter through', () => {
    expect(
      toQueryParams({
        q: 'acme',
        isActive: true,
        projectId: 3,
        supportPlan: 'Premium',
        accountManagerId: 2,
      }),
    ).toEqual({
      q: 'acme',
      isActive: true,
      projectId: 3,
      supportPlan: 'Premium',
      accountManagerId: 2,
    })
  })
})

describe('formatLastTicketDate', () => {
  /**
   * "Never" and "—" say different things: one is a client nothing has been
   * raised against, the other is a value we could not read. Collapsing them
   * would hide a parsing bug behind a legitimate state.
   */
  it('reads null as Never', () => {
    expect(formatLastTicketDate(null)).toBe('Never')
    expect(formatLastTicketDate(undefined)).toBe('Never')
    expect(formatLastTicketDate('')).toBe('Never')
  })

  it('reads an unparseable value as an em dash, not as Never', () => {
    expect(formatLastTicketDate('not-a-date')).toBe('—')
  })

  it('renders a real instant', () => {
    // Rendered in the viewer's own zone — PLAN.md §3.1 stores UTC and applies
    // the timezone here. Asserted loosely for that reason: the exact string is
    // the runner's locale, and pinning it would fail on somebody else's machine.
    expect(formatLastTicketDate('2026-08-01T09:15:00Z')).toMatch(/2026/)
  })
})
