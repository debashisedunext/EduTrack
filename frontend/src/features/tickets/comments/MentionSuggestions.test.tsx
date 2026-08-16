import * as React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'

import { getDb } from '@/mocks/db'
import { MentionSuggestions, type MentionKeyboard } from './MentionSuggestions'

/**
 * C-030 · the candidate lookup and the keys that drive it, through the mock
 * server.
 *
 * `MentionTypeahead.test.tsx` covers the listbox as a screen reader sees it and
 * `mention-token.test.ts` covers when it opens. What is left — and what is worth
 * a real request rather than a stub — is that the query is scoped to the
 * project. The mock's `/users` handler applies the same `projectId` and `q`
 * filters `ResourceRepository` does, so a picker offering somebody the server
 * would refuse shows up here rather than in production, where it would fail
 * silently: an unresolved mention is plain text by design, so nobody is told the
 * notification went nowhere.
 *
 * The keyboard is exercised through the imperative handle rather than through
 * `CommentBox`, because reaching it from there needs a caret and jsdom has no
 * real selection — the constraint `CommentBox.test.tsx` records at the top of
 * its own file.
 */

function renderSuggestions(props: { projectId: number; query: string; onPick?: (h: string) => void }) {
  const ref = React.createRef<MentionKeyboard>()
  const onPick = props.onPick ?? vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  render(
    <QueryClientProvider client={queryClient}>
      <MentionSuggestions
        ref={ref}
        projectId={props.projectId}
        query={props.query}
        anchor={{ left: 10, top: 20 }}
        onPick={onPick}
      />
    </QueryClientProvider>,
  )
  return { ref, onPick }
}

/** A project with members, and one of its members, straight from the fixture. */
function aProjectWithMembers() {
  const db = getDb()
  const member = db.users.find((u) => u.isActive && u.projectIds.length > 0)
  if (!member) throw new Error('fixture has no active project member')
  return { projectId: member.projectIds[0], member }
}

describe('MentionSuggestions', () => {
  it('offers the project’s own members', async () => {
    const { projectId, member } = aProjectWithMembers()

    renderSuggestions({ projectId, query: '' })

    await waitFor(() => expect(screen.getByRole('listbox')).toBeInTheDocument())
    expect(screen.getByText(`@${member.username}`)).toBeInTheDocument()
  })

  it('offers nobody from another project', async () => {
    const db = getDb()
    const { projectId, member } = aProjectWithMembers()
    const outsider = db.users.find((u) => u.isActive && !u.projectIds.includes(projectId))
    if (!outsider) throw new Error('fixture has no non-member to test with')

    renderSuggestions({ projectId, query: '' })

    await waitFor(() => expect(screen.getByText(`@${member.username}`)).toBeInTheDocument())
    // The membership check is the server's and this only mirrors it, but a
    // picker that offers an outsider is a picker that posts a mention nothing
    // notifies.
    expect(screen.queryByText(`@${outsider.username}`)).not.toBeInTheDocument()
  })

  it('narrows on what has been typed after the @', async () => {
    const { projectId, member } = aProjectWithMembers()

    renderSuggestions({ projectId, query: member.username })

    await waitFor(() => expect(screen.getByText(`@${member.username}`)).toBeInTheDocument())
    expect(screen.getAllByRole('option')).toHaveLength(1)
  })

  it('renders nothing when a query matches no member', async () => {
    const { projectId } = aProjectWithMembers()

    renderSuggestions({ projectId, query: 'nobody-by-this-name' })

    // Rather than an empty popup. The handle stays plain text, which is already
    // the correct outcome for a name that is not on the project.
    await waitFor(() => expect(screen.queryByRole('listbox')).not.toBeInTheDocument())
  })

  describe('the keyboard', () => {
    it('picks the highlighted candidate on Enter', async () => {
      const { projectId } = aProjectWithMembers()
      const onPick = vi.fn()
      const { ref } = renderSuggestions({ projectId, query: '', onPick })

      await waitFor(() => expect(screen.getByRole('listbox')).toBeInTheDocument())
      const first = handleOf(screen.getAllByRole('option')[0])

      // The handle, not the display name — the handle is what gets inserted and
      // what the server parses back out of the body.
      expect(ref.current?.handleKey(keyEvent('Enter'))).toBe(true)
      expect(onPick).toHaveBeenCalledWith(first)
    })

    it('moves the highlight with the arrow keys, and wraps', async () => {
      const { projectId } = aProjectWithMembers()
      const { ref } = renderSuggestions({ projectId, query: '' })

      await waitFor(() => expect(screen.getByRole('listbox')).toBeInTheDocument())
      const count = screen.getAllByRole('option').length
      if (count < 2) return

      expect(ref.current?.handleKey(keyEvent('ArrowDown'))).toBe(true)
      await waitFor(() =>
        expect(screen.getAllByRole('option')[1]).toHaveAttribute('aria-selected', 'true'),
      )

      // Up from the second lands on the first; up again wraps to the last.
      ref.current?.handleKey(keyEvent('ArrowUp'))
      ref.current?.handleKey(keyEvent('ArrowUp'))
      await waitFor(() =>
        expect(screen.getAllByRole('option')[count - 1]).toHaveAttribute('aria-selected', 'true'),
      )
    })

    it('lets Ctrl+Enter through, so posting still works with the list open', async () => {
      const { projectId } = aProjectWithMembers()
      const onPick = vi.fn()
      const { ref } = renderSuggestions({ projectId, query: '', onPick })

      await waitFor(() => expect(screen.getByRole('listbox')).toBeInTheDocument())

      // Somebody holding the modifier has decided to send. Swallowing it to
      // complete a name would post nothing and look broken.
      expect(ref.current?.handleKey(keyEvent('Enter', { ctrlKey: true }))).toBe(false)
      expect(onPick).not.toHaveBeenCalled()
    })

    it('does not claim keys it has no use for', async () => {
      const { projectId } = aProjectWithMembers()
      const { ref } = renderSuggestions({ projectId, query: '' })

      await waitFor(() => expect(screen.getByRole('listbox')).toBeInTheDocument())

      // Every unclaimed key has to reach the editor untouched, or typing breaks.
      expect(ref.current?.handleKey(keyEvent('a'))).toBe(false)
      expect(ref.current?.handleKey(keyEvent('Backspace'))).toBe(false)
    })
  })
})

/** The `@handle` an option renders, read from its text rather than its markup. */
function handleOf(option: HTMLElement): string {
  const match = /@([A-Za-z0-9._-]+)/.exec(option.textContent ?? '')
  if (!match) throw new Error(`option renders no handle: ${option.textContent}`)
  return match[1]
}

/** Enough of a React keyboard event for the handler under test. */
function keyEvent(key: string, modifiers: { ctrlKey?: boolean; metaKey?: boolean } = {}) {
  return {
    key,
    ctrlKey: modifiers.ctrlKey ?? false,
    metaKey: modifiers.metaKey ?? false,
    preventDefault: vi.fn(),
  } as unknown as React.KeyboardEvent
}
