import { describe, expect, it } from 'vitest'

import {
  REASSIGN_WIZARD_ROUTE,
  isSafeReturnPath,
  reassignWizardHref,
  resumeDeactivationHref,
  resumeDeactivationTarget,
  withoutResumeMarker,
} from './reassignHandoff'

/**
 * B-014 · the contract between the Resource Master and Stream C's S-24 wizard.
 *
 * These are the assertions that matter when the wizard lands: if C-063 reads
 * `fromUserId` and `returnTo` and this file still passes, the two halves fit.
 * Tested here rather than only through the page because the round trip is the
 * behaviour — building the link is half of it, and reading the marker back off
 * a URL somebody may have edited is the other half.
 */
describe('the link into the wizard', () => {
  it('names the source resource and the way back', () => {
    const url = new URL(reassignWizardHref({ fromUserId: 42 }), 'http://localhost')

    expect(url.pathname).toBe(REASSIGN_WIZARD_ROUTE)
    expect(url.searchParams.get('fromUserId')).toBe('42')
    expect(url.searchParams.get('returnTo')).toBe('/masters/resources?deactivate=42')
  })

  it('preserves the filters the admin was working in', () => {
    const url = new URL(
      reassignWizardHref({ fromUserId: 42, returnSearch: '?role=DEVELOPER&isActive=true' }),
      'http://localhost',
    )
    const returnTo = new URL(url.searchParams.get('returnTo')!, 'http://localhost')

    expect(returnTo.searchParams.get('role')).toBe('DEVELOPER')
    expect(returnTo.searchParams.get('isActive')).toBe('true')
    expect(returnTo.searchParams.get('deactivate')).toBe('42')
  })

  it('does not stack a second marker when one is already there', () => {
    // The admin can reach the wizard from a page that itself arrived with a
    // resume marker — a refusal on the return leg is exactly that. Two
    // `deactivate` parameters would be read as whichever came first, which is
    // the sort of thing that works until the two are different people.
    const returnTo = new URL(
      resumeDeactivationHref(42, '?deactivate=7&role=QA'),
      'http://localhost',
    )

    expect(returnTo.searchParams.getAll('deactivate')).toEqual(['42'])
    expect(returnTo.searchParams.get('role')).toBe('QA')
  })
})

describe('reading the marker back', () => {
  it('finds the id it wrote', () => {
    expect(resumeDeactivationTarget('?deactivate=42&role=QA')).toBe(42)
  })

  it.each(['', '?role=QA', '?deactivate=', '?deactivate=abc', '?deactivate=-1', '?deactivate=1.5'])(
    'reads %j as no resumption rather than as NaN',
    (search) => {
      // The alternative is a `PATCH /users/NaN/status` — a 400 the admin can do
      // nothing about, raised by a URL they probably did not type.
      expect(resumeDeactivationTarget(search)).toBeNull()
    },
  )

  it('strips the marker without disturbing the rest', () => {
    expect(withoutResumeMarker('?role=QA&deactivate=42&isActive=true')).toBe(
      '?role=QA&isActive=true',
    )
  })

  it('leaves nothing behind when the marker was the only parameter', () => {
    // Not `'?'` — a bare question mark is a URL change React Router will happily
    // navigate to again.
    expect(withoutResumeMarker('?deactivate=42')).toBe('')
  })
})

describe('a returnTo handed back to us', () => {
  it.each(['/masters/resources', '/masters/resources?deactivate=42'])('accepts %j', (path) => {
    expect(isSafeReturnPath(path)).toBe(true)
  })

  it.each([
    'https://evil.example/steal',
    '//evil.example',
    '/\\evil.example',
    'javascript:alert(1)',
    '',
    null,
    undefined,
  ])('refuses %j', (path) => {
    // `navigate(searchParams.get('returnTo'))` reads as obviously fine and is an
    // open redirect. Stream C needs this check on the day the wizard honours the
    // parameter; it is written here so that it exists to be imported.
    expect(isSafeReturnPath(path)).toBe(false)
  })
})
