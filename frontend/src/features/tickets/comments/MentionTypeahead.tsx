import { cn } from '@/lib/utils'
import type { MentionCandidate } from './useMentionCandidates'

/**
 * C-030 · the listbox the `@` type-ahead drops under the caret.
 *
 * ## Accessibility is the reason this is not a `<div>` of `<button>`s
 *
 * CLAUDE.md makes WCAG AA and keyboard navigation non-optional, and a
 * type-ahead over a text box is the APG **combobox with a listbox popup**: the
 * editable keeps focus throughout, the arrow keys move a *visual* highlight, and
 * `aria-activedescendant` is what tells a screen reader which option that is.
 * Moving real focus into the list would take the caret out of the comment, which
 * is both wrong and unrecoverable without a mouse.
 *
 * `onMouseDown` rather than `onClick` on an option, and `preventDefault` with
 * it: a click blurs the editable first, and the selection this inserts into is
 * gone by the time `click` fires. This is the same reason the editor's own link
 * dialog exists as it does.
 *
 * ## Rendered in place, not in a portal
 *
 * `position: fixed` off the caret's viewport rect, inside `CommentBox`'s own
 * tree. A portal would need its own focus and dismissal management for a list
 * that is already dismissed by every route out of the box, and the comment box
 * is never inside a scrolling container of its own — the page scrolls, and a
 * scroll closes the list.
 */
export function MentionTypeahead({
  candidates,
  isLoading,
  activeIndex,
  anchor,
  listboxId,
  optionId,
  onPick,
}: {
  candidates: MentionCandidate[]
  isLoading: boolean
  /** Which option the arrow keys have landed on. */
  activeIndex: number
  anchor: { left: number; top: number }
  listboxId: string
  /** Stable per-option id, so `aria-activedescendant` can name one. */
  optionId: (index: number) => string
  onPick: (candidate: MentionCandidate) => void
}) {
  // Nothing at all rather than an empty box: a popup reading "no matches" under
  // every `@` in a sentence that was never a mention is noise, and the handle
  // staying plain text is already the correct outcome.
  if (candidates.length === 0) {
    return null
  }

  return (
    <ul
      id={listboxId}
      role="listbox"
      aria-label="Mention a project member"
      aria-busy={isLoading || undefined}
      className={cn(
        'fixed z-50 max-h-64 w-72 overflow-y-auto rounded-card border border-border',
        'bg-surface py-1 shadow-modal',
      )}
      style={{ left: anchor.left, top: anchor.top + 4 }}
    >
      {candidates.map((candidate, index) => (
        <li
          key={candidate.id}
          id={optionId(index)}
          role="option"
          aria-selected={index === activeIndex}
          // `mousedown` + `preventDefault`, never `click` — see the note above.
          // By the time `click` fires the editable has blurred and the caret
          // this inserts into no longer exists.
          onMouseDown={(event) => {
            event.preventDefault()
            onPick(candidate)
          }}
          className={cn(
            'flex cursor-pointer items-baseline gap-2 px-3 py-1.5 text-base text-content',
            index === activeIndex && 'bg-subtle',
          )}
        >
          <span className="truncate font-medium">{candidate.displayName}</span>
          {/*
            The handle is shown beside the name because the handle is what gets
            inserted and what the server parses back. Two people called Ravi are
            told apart by `ravi.kumar` and `ravi.s`, and a list showing only
            display names would make that choice a guess.
          */}
          <span className="truncate text-caption text-content-muted">@{candidate.handle}</span>
          {candidate.role && (
            <span className="ml-auto shrink-0 text-caption text-content-muted">
              {candidate.role}
            </span>
          )}
        </li>
      ))}
    </ul>
  )
}
