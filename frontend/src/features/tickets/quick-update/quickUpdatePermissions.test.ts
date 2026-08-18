import { describe, expect, it } from 'vitest'

import { canChangeLevelHere } from './quickUpdatePermissions'

/**
 * C-037 · the blueprint's own exception to S-21's exclusion list — "level
 * (unless PM)" — as a pure function, on `commentPermissions.test.ts`'s
 * argument: a rule buried in a component can only be tested through jsdom,
 * and the one rule that matters here is exactly the kind a rendering test
 * would assert weakly for whatever reason happened to be true.
 */
describe('canChangeLevelHere — S-21’s PM exception', () => {
  it('allows PM', () => {
    expect(canChangeLevelHere('PM')).toBe(true)
  })

  it('refuses every other role, including the two the detail page’s own chip allows', () => {
    expect(canChangeLevelHere('ADMIN')).toBe(false)
    expect(canChangeLevelHere('SUPPORT')).toBe(false)
    expect(canChangeLevelHere('DEVELOPER')).toBe(false)
    expect(canChangeLevelHere('QA')).toBe(false)
    expect(canChangeLevelHere('DEPLOYMENT')).toBe(false)
  })

  it('refuses while the viewer has not resolved yet', () => {
    expect(canChangeLevelHere(undefined)).toBe(false)
  })
})
