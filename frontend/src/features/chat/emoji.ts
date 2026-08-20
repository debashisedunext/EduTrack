/**
 * D-053 · the emoji half of §7.6's "file and image share, emoji, message
 * search".
 *
 * ## Why a curated list rather than an emoji library
 *
 * `emoji-mart` and its peers ship the full Unicode set with search index and
 * sprite sheets — 1–2 MB before compression, for a feature §7.6 gives half a
 * clause to. This is a work chat inside a ticketing tool: the emoji people
 * actually send are acknowledgements, and a short palette they can hit without
 * scrolling beats a searchable index of six thousand they will never open.
 *
 * The cost of being wrong is also low and recoverable: nothing here is stored,
 * validated or rendered specially. `chat_messages.body` is `utf8mb4` (verified
 * against the live column, not only the DDL), so **any** emoji a user pastes or
 * types with their own system picker is already stored and displayed correctly
 * today. This palette adds a way to reach the common ones without leaving the
 * keyboard; it does not decide what is representable.
 *
 * ## Skin tones and ZWJ sequences are deliberately absent
 *
 * A skin-tone modifier is a second code point, and offering five variants of
 * each hand turns a 40-cell grid into 200. Anyone who wants one has a system
 * picker that already knows their preference — and picking a default tone *for*
 * people is the version of this feature worth avoiding.
 */
export interface EmojiGroup {
  /** The group label, read out by the picker's `aria-label` and shown above the grid. */
  name: string
  emoji: readonly string[]
}

export const EMOJI_GROUPS: readonly EmojiGroup[] = [
  {
    name: 'Reactions',
    emoji: ['👍', '👎', '👏', '🙌', '🙏', '💪', '🤝', '👀'],
  },
  {
    name: 'Faces',
    emoji: ['🙂', '😄', '😅', '😂', '😉', '😌', '🤔', '😐', '😬', '😴', '🤯', '😭'],
  },
  {
    name: 'Work',
    emoji: ['✅', '❌', '⚠️', '🚨', '🔥', '🐛', '🚀', '⏰', '📌', '📎', '📝', '🔍'],
  },
  {
    name: 'Outcome',
    emoji: ['🎉', '🎯', '💡', '❓', '❗', '🔁', '🚧', '🧪'],
  },
]

/** Every emoji the palette offers, flattened — the picker's roving focus order. */
export const ALL_EMOJI: readonly string[] = EMOJI_GROUPS.flatMap((group) => group.emoji)

export interface Insertion {
  body: string
  /** Where the caret belongs afterwards — immediately after what was inserted. */
  caret: number
}

/**
 * Insert `emoji` into `body` at the caret, replacing any selection.
 *
 * **At the caret, not appended.** Appending is the version of this that looks
 * right in every manual test — because a test types a message and then picks an
 * emoji — and is wrong the first time somebody goes back to add one mid-sentence.
 *
 * `selectionStart`/`selectionEnd` on a `<textarea>` are clamped rather than
 * trusted: React can re-render between the click on the emoji and this call, and
 * a stale caret past the end of a shortened body would otherwise produce
 * `undefined` inside the string.
 *
 * A space is added after the emoji **only when the next character is not
 * already whitespace**, so typing several in a row does not accumulate gaps and
 * inserting before an existing word does not glue the two together.
 */
export function insertEmoji(body: string, selectionStart: number, selectionEnd: number, emoji: string): Insertion {
  const start = clamp(selectionStart, body.length)
  const end = clamp(Math.max(selectionEnd, selectionStart), body.length)

  const before = body.slice(0, start)
  const after = body.slice(end)
  const separator = after.length === 0 || /^\s/.test(after) ? '' : ' '

  return {
    body: before + emoji + separator + after,
    // Past the emoji and past any space we added — the next keystroke
    // continues the sentence rather than landing inside it.
    caret: before.length + emoji.length + separator.length,
  }
}

function clamp(value: number, max: number): number {
  if (!Number.isFinite(value) || value < 0) return max
  return Math.min(value, max)
}
