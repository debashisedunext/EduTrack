import { describe, expect, it } from 'vitest'

import { DEFAULT_DESTINATION, parseWizardHandoff, wizardDestination } from './bulkReassignWizard'

/**
 * C-063 · the query-string half of the S-24 / B-014 handoff.
 *
 * `reassignHandoff.test.ts` pins the sending side — the URL Stream B builds.
 * This file pins the receiving side: what this wizard does with that URL,
 * including a URL nobody built on purpose.
 */
describe('parseWizardHandoff', () => {
  it('reads a well-formed handoff', () => {
    expect(parseWizardHandoff('?fromUserId=42&returnTo=%2Fmasters%2Fresources%3Fdeactivate%3D42')).toEqual({
      fromUserId: 42,
      returnTo: '/masters/resources?deactivate=42',
    })
  })

  it('works with neither parameter — the wizard still has to open', () => {
    expect(parseWizardHandoff('')).toEqual({ fromUserId: null, returnTo: null })
  })

  it.each(['', 'abc', '-1', '1.5', '0'])('reads fromUserId=%j as no preselection, not NaN', (raw) => {
    // The alternative is `GET /tickets?assigneeId=NaN`, a 400 the admin did
    // not cause and cannot fix from this screen.
    expect(parseWizardHandoff(`?fromUserId=${raw}`).fromUserId).toBeNull()
  })

  it.each([
    'https://evil.example/steal',
    '//evil.example',
    '/\\evil.example',
    'javascript:alert(1)',
  ])('treats an unsafe returnTo=%j as absent, not as a value to navigate to', (unsafe) => {
    expect(parseWizardHandoff(`?returnTo=${encodeURIComponent(unsafe)}`).returnTo).toBeNull()
  })

  it('accepts an app-relative returnTo with its own query string', () => {
    expect(parseWizardHandoff('?returnTo=%2Ftickets%3Ffoo%3Dbar').returnTo).toBe('/tickets?foo=bar')
  })
})

describe('wizardDestination', () => {
  it('uses the given return path when there is one', () => {
    expect(wizardDestination('/masters/resources?deactivate=42')).toBe('/masters/resources?deactivate=42')
  })

  it('falls back to the tickets list when there is none', () => {
    expect(wizardDestination(null)).toBe(DEFAULT_DESTINATION)
  })
})
