import { describe, expect, it } from 'vitest'

import { EMAIL_MESSAGE, isWellFormedEmail } from './email'

/**
 * B-028 · **the same corpus `EmailFormatTest` asserts on the server.**
 *
 * That is the point of the file rather than a coincidence. The rule has to
 * exist twice — the two run on different machines — so the only thing that can
 * keep them together is asserting them against identical cases. A relaxation
 * made on one side and not the other fails here or there, whichever was left
 * behind.
 */
describe('isWellFormedEmail', () => {
  it.each([
    'sara@acme.example',
    'accounts.payable@acme.co.in',
    'support+tickets@bluewave.example',
    'a@b.co',
  ])('accepts %s', (candidate) => {
    expect(isWellFormedEmail(candidate)).toBe(true)
  })

  /**
   * The disagreement B-028 resolved. zod's `.email()` accepts every one of
   * these, and so did Jakarta's `@Email` on the server — while B-030's importer
   * refused them on the same columns.
   */
  it.each(['accounts@acme', 'bob@localhost', 'sara@acme.', 'sara@.example'])(
    'refuses %s — no dotted TLD, the case the old rules disagreed on',
    (candidate) => {
      expect(isWellFormedEmail(candidate)).toBe(false)
    },
  )

  it.each([
    'sara@acme.example, ravi@acme.example',
    'sara@acme.example; ravi@acme.example',
    'sara acme.example',
    'acme.example',
    '@acme.example',
    'sara@@acme.example',
  ])('refuses %s', (candidate) => {
    expect(isWellFormedEmail(candidate)).toBe(false)
  })

  it('trims before matching, so a pasted address is not refused for its whitespace', () => {
    expect(isWellFormedEmail('  sara@acme.example \n')).toBe(true)
  })

  it('treats null, undefined and blank as not-valid, leaving "required" to the caller', () => {
    expect(isWellFormedEmail(null)).toBe(false)
    expect(isWellFormedEmail(undefined)).toBe(false)
    expect(isWellFormedEmail('')).toBe(false)
    expect(isWellFormedEmail('   ')).toBe(false)
  })

  it('exports one message, so three screens word the same rejection identically', () => {
    expect(EMAIL_MESSAGE).toBe('That is not a well-formed email address.')
  })
})
