/**
 * C-030 · "is the caret inside an `@handle` right now, and what has been typed
 * so far?"
 *
 * Pure string work, deliberately separated from the DOM plumbing in
 * `useMentionTypeahead`. jsdom implements neither `execCommand` nor a real
 * selection — `CommentBox.test.tsx` says so at the top and drives the editor
 * through `fireEvent.input` for that reason — so a rule buried in a selection
 * handler is a rule that cannot be tested. These are the rules; the hook is the
 * wiring.
 *
 * ## The rules match the server's, on purpose
 *
 * `CommentMentionParser` decides who actually gets notified, and a type-ahead
 * that opens on text the server will not parse is a type-ahead that offers
 * somebody the write path then ignores. The two that matter:
 *
 * - **The character before `@` may not be a word character**, which is the
 *   server's lookbehind. Typing `ravi@edunext.com` must not pop a listbox
 *   offering to notify whoever owns `edunext` as a username, and comments quote
 *   addresses constantly.
 * - **A handle is `[A-Za-z0-9._-]`, at most 50 characters**, which is
 *   `users.username`'s own width and character set.
 *
 * The one deliberate difference: the server requires the first character after
 * `@` to be alphanumeric, and this admits an empty query. A bare `@` is what
 * somebody has typed a moment before they type a name, and refusing to open the
 * list until the first letter lands means the feature is invisible to anyone who
 * does not already know it exists.
 */

/** `users.username` is `VARCHAR(50)`. Longer is not a handle. */
const MAX_HANDLE_LENGTH = 50

/** The character class of a handle — `ravi.kumar`, `ravi-k`, `ravi_k`. */
const HANDLE_CHARS = /^[A-Za-z0-9._-]*$/

/**
 * The server's lookbehind, as a positive test. `@` included: `@ravi@meera` back
 * to back finds only the first, which is the same false negative the server
 * accepts and a shape nobody types.
 */
const WORD_BEFORE = /[A-Za-z0-9._@-]/

export interface MentionToken {
  /** What has been typed after the `@`, possibly empty. */
  query: string
  /** Index of the `@` itself, so an insertion knows what to replace. */
  start: number
  /** One past the last character of the token — the caret. */
  end: number
}

/**
 * @param text     the text of the line the caret sits in, or the whole text node
 * @param caret    offset of the caret within `text`
 * @returns the token the caret is inside, or null if it is not inside one
 */
export function findMentionToken(text: string, caret: number): MentionToken | null {
  if (caret < 0 || caret > text.length) {
    return null
  }

  const before = text.slice(0, caret)
  const at = before.lastIndexOf('@')
  if (at < 0) {
    return null
  }

  // Everything between the '@' and the caret has to still be a handle. A space,
  // a newline or a comma closes the token — "@ravi and @meera" must offer
  // completions for `meera`, not for `ravi and meera`.
  const query = before.slice(at + 1)
  if (query.length > MAX_HANDLE_LENGTH || !HANDLE_CHARS.test(query)) {
    return null
  }

  // The lookbehind. `at === 0` is a token at the very start of the text, which
  // has nothing before it and is therefore fine.
  if (at > 0 && WORD_BEFORE.test(text.charAt(at - 1))) {
    return null
  }

  return { query, start: at, end: caret }
}

/**
 * What replaces the token when a candidate is chosen.
 *
 * The trailing space is not cosmetic: without it the caret sits immediately
 * after the handle, `findMentionToken` still matches, and the listbox reopens on
 * the name that was just picked. The space closes the token, which is the same
 * thing that closes it when somebody types one.
 */
export function mentionReplacement(handle: string): string {
  return `@${handle} `
}
