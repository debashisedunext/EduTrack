import { describe, expect, it } from 'vitest'

import { formatTemplateTotalTatDays, templateTotalTatDays } from './journeyTemplateTat'

/** A task, with only the three fields the critical path is walked from. */
const task = (id: number, tatDays: number, dependsOnStepId: number | null = null) => ({
  id,
  tatDays,
  dependsOnStepId,
})

describe('templateTotalTatDays', () => {
  it('takes the longer of two parallel tasks rather than their sum', () => {
    // The rule in one line: A (1 day) and B (2 days) wait for nothing, so
    // both start on day 1 and the service turns round in 2 days, not 3.
    expect(templateTotalTatDays([task(1, 1), task(2, 2)])).toBe(2)
  })

  it('sums a chain, because a dependency really is consecutive days', () => {
    // The same two tasks, B now held by A: day 1, then days 2–3.
    expect(templateTotalTatDays([task(1, 1), task(2, 2, 1)])).toBe(3)
  })

  it('follows the longest chain when several run alongside each other', () => {
    // Chain A→B is 2+3 = 5 days; C alone is 4. The plan ends on day 5, and
    // shortening C would not finish the service a day sooner.
    expect(
      templateTotalTatDays([task(1, 2), task(2, 3, 1), task(3, 4)]),
    ).toBe(5)
  })

  it('adds each link of a deep chain', () => {
    expect(
      templateTotalTatDays([task(1, 2), task(2, 2, 1), task(3, 2, 2)]),
    ).toBe(6)
  })

  it('counts a dependency that crosses stages exactly like any other', () => {
    // Nothing here knows about stages: the walk is over dependsOnStepId, so
    // Data Migration held by Configuration adds whether or not the two are
    // drawn in the same container.
    expect(templateTotalTatDays([task(10, 3), task(20, 5, 10)])).toBe(8)
  })

  it('is zero for a template with no tasks yet', () => {
    expect(templateTotalTatDays([])).toBe(0)
  })
})

describe('formatTemplateTotalTatDays', () => {
  it('pluralises', () => {
    expect(formatTemplateTotalTatDays(0)).toBe('0 working days')
    expect(formatTemplateTotalTatDays(1)).toBe('1 working day')
    expect(formatTemplateTotalTatDays(5)).toBe('5 working days')
  })
})
