import { describe, expect, it } from 'vitest'

import { findMentionToken, mentionReplacement } from './mention-token'

/**
 * C-030 · the rules that decide whether the type-ahead opens.
 *
 * These live here rather than in a component test for the reason
 * `CommentBox.test.tsx` gives at the top of its own file: jsdom implements
 * neither `execCommand` nor a real selection, so anything that depends on a
 * caret can only be exercised as a pure function. That is why the rules were
 * pulled out of the hook in the first place.
 *
 * The cases below deliberately mirror `CommentMentionParserTest`'s. A
 * type-ahead that opens on text the server will not parse offers a name and
 * then silently fails to notify it, which is the worst outcome available here —
 * an unresolved mention is indistinguishable from plain text by design, so
 * nothing tells the author it went nowhere.
 */

describe('findMentionToken', () => {
  it('finds a token the caret is sitting inside', () => {
    // "hi @rav|"
    expect(findMentionToken('hi @rav', 7)).toEqual({ query: 'rav', start: 3, end: 7 })
  })

  it('opens on a bare @, before anything has been typed', () => {
    // The one deliberate difference from the server, which requires the first
    // character to be alphanumeric. Waiting for a letter would make the feature
    // invisible to anyone who does not already know it exists.
    expect(findMentionToken('hi @', 4)).toEqual({ query: '', start: 3, end: 4 })
  })

  it('finds a token at the very start of the text', () => {
    expect(findMentionToken('@ravi', 5)).toEqual({ query: 'ravi', start: 0, end: 5 })
  })

  it('admits the dots and hyphens of a real username', () => {
    expect(findMentionToken('@ravi.kumar', 11)?.query).toBe('ravi.kumar')
    expect(findMentionToken('@meera-s', 8)?.query).toBe('meera-s')
  })

  it('takes the nearest token, not the first', () => {
    // "@ravi and @mee|" — completing `ravi` here would replace the wrong one.
    expect(findMentionToken('@ravi and @mee', 14)).toEqual({ query: 'mee', start: 10, end: 14 })
  })

  it('closes on whitespace', () => {
    // "@ravi and| " — the token ended two words ago.
    expect(findMentionToken('@ravi and', 9)).toBeNull()
  })

  it('closes on a newline', () => {
    expect(findMentionToken('@ravi\nnext', 10)).toBeNull()
  })

  it('does not open inside an email address', () => {
    // The server's lookbehind. Comments quote addresses constantly, and a
    // listbox popping up over "ops@edunext.com" is both wrong and in the way.
    expect(findMentionToken('mail ops@edunext', 16)).toBeNull()
    expect(findMentionToken('ravi.kumar@edu', 14)).toBeNull()
  })

  it('does not open when the caret is before the @', () => {
    // "hi| @ravi" — typing at the start of a line that happens to contain a
    // handle further along is not composing a mention.
    expect(findMentionToken('hi @ravi', 2)).toBeNull()
  })

  it('closes once the handle exceeds the column width', () => {
    const long = 'a'.repeat(51)
    expect(findMentionToken(`@${long}`, 52)).toBeNull()
  })

  it('has no token in text with no @ at all', () => {
    expect(findMentionToken('nothing here', 12)).toBeNull()
  })

  it('is null rather than throwing for an out-of-range caret', () => {
    // The caret and the text can be a tick out of step while the DOM settles.
    expect(findMentionToken('@ravi', 99)).toBeNull()
    expect(findMentionToken('@ravi', -1)).toBeNull()
  })
})

describe('mentionReplacement', () => {
  it('appends a space so the token closes behind the caret', () => {
    expect(mentionReplacement('ravi.kumar')).toBe('@ravi.kumar ')
  })

  it('produces text the token finder no longer matches', () => {
    // The regression this guards: without the trailing space the caret lands
    // inside a still-valid token and the listbox reopens on the name just
    // picked, which reads as the click having failed.
    const inserted = mentionReplacement('ravi')
    expect(findMentionToken(inserted, inserted.length)).toBeNull()
  })
})
