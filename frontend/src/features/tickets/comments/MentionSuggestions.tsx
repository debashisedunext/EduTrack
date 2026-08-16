import * as React from 'react'

import { MentionTypeahead } from './MentionTypeahead'
import { useMentionCandidates, type MentionCandidate } from './useMentionCandidates'

/**
 * C-030 · everything that only exists while an `@handle` is being typed.
 *
 * ## Why this is a component rather than a hook inside `CommentBox`
 *
 * The candidate lookup is a react-query hook, and a hook called from
 * `CommentBox` is called on every render of `CommentBox` — `enabled: false`
 * suppresses the request but not the `useQuery` call, which still needs a
 * `QueryClientProvider` in context. That would make the composer require a query
 * client to render at all, for a feature that is inert on a ticket with no
 * project and on a sealed cycle.
 *
 * Mounting it only while a token is open is what keeps that dependency scoped to
 * the moment it is real. It also keeps `CommentBox.test.tsx` rendering the
 * component the way every other consumer does — bare, with no provider — which
 * is worth more than it looks: a test file that has to be taught about a
 * dependency it does not exercise is one that stops describing the component.
 *
 * ## The keyboard lives here too
 *
 * ↑/↓/Enter/Tab/Escape only mean anything while a list is visible, and what they
 * do depends on the candidates and the highlight — both of which are this
 * component's state. `CommentBox` offers its keydown to {@link MentionKeyboard}
 * first and carries on if the key was not consumed, so the editor keeps every
 * key it is otherwise entitled to for exactly as long as there is nothing on
 * screen to act on.
 */

export interface MentionKeyboard {
  /** @returns true if the key was consumed and the editor must not see it */
  handleKey: (event: React.KeyboardEvent) => boolean
}

export const MentionSuggestions = React.forwardRef<
  MentionKeyboard,
  {
    projectId: number
    /** What has been typed after the `@`, possibly empty. */
    query: string
    anchor: { left: number; top: number }
    onPick: (handle: string) => void
  }
>(function MentionSuggestions({ projectId, query, anchor, onPick }, ref) {
  const { candidates, isLoading } = useMentionCandidates({
    projectId,
    query,
    enabled: true,
  })

  const [activeIndex, setActiveIndex] = React.useState(0)
  const listboxId = React.useId()
  const optionId = React.useCallback(
    (index: number) => `${listboxId}-option-${index}`,
    [listboxId],
  )

  // Back to the top whenever the candidate set changes. Without this the
  // highlight stays on index 3 as the list shrinks to two, and Enter picks
  // whatever has slid underneath it — which is how somebody notifies the wrong
  // colleague without noticing.
  React.useEffect(() => {
    setActiveIndex(0)
  }, [candidates])

  React.useImperativeHandle(
    ref,
    (): MentionKeyboard => ({
      handleKey(event) {
        if (candidates.length === 0) {
          // Escape is still ours: the list may be empty because the query has
          // not landed, and Escape has to close a popup the user can see.
          return false
        }
        switch (event.key) {
          case 'ArrowDown':
            event.preventDefault()
            setActiveIndex((i) => (i + 1) % candidates.length)
            return true
          case 'ArrowUp':
            event.preventDefault()
            setActiveIndex((i) => (i - 1 + candidates.length) % candidates.length)
            return true
          case 'Enter':
          case 'Tab': {
            // Ctrl/Cmd+Enter still posts, even with the list open. Somebody
            // holding the modifier has decided to send, and swallowing that to
            // complete a name would post nothing and look broken.
            if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) return false
            const picked: MentionCandidate | undefined = candidates[activeIndex]
            if (!picked) return false
            event.preventDefault()
            onPick(picked.handle)
            return true
          }
          default:
            return false
        }
      },
    }),
    [activeIndex, candidates, onPick],
  )

  if (candidates.length === 0) {
    return null
  }

  return (
    <>
      {/*
        The APG combobox pairing, on an element of our own rather than on the
        editable. `RichTextEditor` takes no arbitrary ARIA props and widening it
        for one consumer is the change every other stream would have to absorb,
        so the announcement rides a visually-hidden element inside the same
        control. `aria-owns` is what tells a screen reader the `position: fixed`
        listbox belongs here despite sitting elsewhere in the layout.
      */}
      <div
        role="combobox"
        aria-expanded="true"
        aria-controls={listboxId}
        aria-owns={listboxId}
        aria-haspopup="listbox"
        aria-activedescendant={optionId(activeIndex)}
        aria-label="Mention a project member"
        className="sr-only"
      />
      <MentionTypeahead
        candidates={candidates}
        isLoading={isLoading}
        activeIndex={activeIndex}
        anchor={anchor}
        listboxId={listboxId}
        optionId={optionId}
        onPick={(candidate) => onPick(candidate.handle)}
      />
    </>
  )
})
