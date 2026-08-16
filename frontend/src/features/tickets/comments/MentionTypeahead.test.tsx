import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { MentionTypeahead } from './MentionTypeahead'
import type { MentionCandidate } from './useMentionCandidates'

/**
 * C-030 · the listbox, as a screen reader and a mouse see it.
 *
 * The caret plumbing is not testable in jsdom (see `mention-token.test.ts`), so
 * what is asserted here is the half that is: the APG combobox roles CLAUDE.md
 * makes non-optional, and the `mousedown`-not-`click` rule that is the whole of
 * whether clicking a name works at all.
 */

const CANDIDATES: MentionCandidate[] = [
  { id: 1, handle: 'ravi.kumar', displayName: 'Ravi Kumar', role: 'DEVELOPER' },
  { id: 2, handle: 'meera.s', displayName: 'Meera S', role: 'QA' },
]

function renderList(overrides: Partial<Parameters<typeof MentionTypeahead>[0]> = {}) {
  const onPick = vi.fn()
  render(
    <MentionTypeahead
      candidates={CANDIDATES}
      isLoading={false}
      activeIndex={0}
      anchor={{ left: 100, top: 200 }}
      listboxId="mentions"
      optionId={(index) => `mentions-option-${index}`}
      onPick={onPick}
      {...overrides}
    />,
  )
  return { onPick }
}

describe('MentionTypeahead', () => {
  it('renders a listbox of options', () => {
    renderList()

    expect(screen.getByRole('listbox', { name: 'Mention a project member' })).toBeInTheDocument()
    expect(screen.getAllByRole('option')).toHaveLength(2)
  })

  it('marks exactly one option selected, and gives each the id aria-activedescendant names', () => {
    renderList({ activeIndex: 1 })

    const options = screen.getAllByRole('option')
    expect(options[0]).toHaveAttribute('aria-selected', 'false')
    expect(options[1]).toHaveAttribute('aria-selected', 'true')
    // The ids have to match what `CommentBox` puts in `aria-activedescendant`,
    // or the announcement points at nothing.
    expect(options[1]).toHaveAttribute('id', 'mentions-option-1')
  })

  it('shows the handle beside the display name', () => {
    renderList()

    // Two people called Ravi are told apart by the handle, and the handle is
    // also what gets inserted — a list of display names alone makes the choice
    // a guess.
    expect(screen.getByText('@ravi.kumar')).toBeInTheDocument()
    expect(screen.getByText('Ravi Kumar')).toBeInTheDocument()
  })

  it('picks on mousedown, not on click', () => {
    const { onPick } = renderList()

    fireEvent.mouseDown(screen.getByText('Meera S'))

    // By the time `click` fires the editable has blurred and the caret the
    // insertion needs is gone. This is the assertion that stops someone
    // "tidying" it into onClick.
    expect(onPick).toHaveBeenCalledWith(CANDIDATES[1])
  })

  it('prevents the default so the editable keeps focus', () => {
    renderList()

    const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true })
    fireEvent(screen.getByText('Ravi Kumar'), event)

    expect(event.defaultPrevented).toBe(true)
  })

  it('renders nothing at all when there are no candidates', () => {
    renderList({ candidates: [] })

    // Rather than an empty popup reading "no matches". Every `@` in a sentence
    // that was never a mention would otherwise drop a box over the text, and
    // the handle staying plain text is already the correct outcome.
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('announces a refresh in flight without emptying the list', () => {
    renderList({ isLoading: true })

    expect(screen.getByRole('listbox')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getAllByRole('option')).toHaveLength(2)
  })
})
