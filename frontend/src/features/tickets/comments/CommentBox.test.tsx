import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'

import { CommentBox } from './CommentBox'

/**
 * C-029 · the composer, blueprint §4B.5.
 *
 * `RichTextEditor` is a contentEditable and jsdom implements neither
 * `execCommand` nor a real selection, so these tests drive it the way the
 * component itself does — by setting `innerHTML` and firing `input`, which is
 * exactly what the editor's `onInput` handler reads. Typing through
 * `userEvent.type` would exercise jsdom's contentEditable stub rather than this
 * component. C-066's own test file settled this and the reasoning is repeated
 * here because it looks like a shortcut and is not.
 */

const editor = () => screen.getByRole('textbox', { name: 'Comment' })

function write(html: string) {
  const box = editor()
  box.innerHTML = html
  fireEvent.input(box)
}

describe('CommentBox', () => {
  it('will not post an empty comment', () => {
    render(<CommentBox onPost={vi.fn()} isPosting={false} postError={null} />)

    expect(screen.getByRole('button', { name: 'Post' })).toBeDisabled()
  })

  /**
   * The single most common contentEditable bug: a required-field check written
   * as `value.trim() === ''` passes on an empty editor, because the browser
   * leaves `<p><br></p>` behind — thirteen characters of nothing.
   */
  it('treats an editor holding only <p><br></p> as empty', () => {
    render(<CommentBox onPost={vi.fn()} isPosting={false} postError={null} />)

    write('<p><br></p>')

    expect(screen.getByRole('button', { name: 'Post' })).toBeDisabled()
  })

  it('enables Post once there is something to say', () => {
    render(<CommentBox onPost={vi.fn()} isPosting={false} postError={null} />)

    write('<p>Root cause is the retry timeout.</p>')

    expect(screen.getByRole('button', { name: 'Post' })).toBeEnabled()
  })

  it('posts the body when Post is pressed', async () => {
    const onPost = vi.fn().mockResolvedValue(undefined)
    render(<CommentBox onPost={onPost} isPosting={false} postError={null} />)

    write('<p>Patch pushed, ready for QA.</p>')
    fireEvent.click(screen.getByRole('button', { name: 'Post' }))

    await waitFor(() =>
      expect(onPost).toHaveBeenCalledWith('<p>Patch pushed, ready for QA.</p>', false),
    )
  })

  describe('§4B.5 · Ctrl/Cmd+Enter to post', () => {
    it('posts on Ctrl+Enter', async () => {
      const onPost = vi.fn().mockResolvedValue(undefined)
      render(<CommentBox onPost={onPost} isPosting={false} postError={null} />)

      write('<p>hello</p>')
      fireEvent.keyDown(editor(), { key: 'Enter', ctrlKey: true })

      await waitFor(() => expect(onPost).toHaveBeenCalledWith('<p>hello</p>', false))
    })

    /** macOS. A shortcut that only works on Windows reads as broken. */
    it('posts on Cmd+Enter', async () => {
      const onPost = vi.fn().mockResolvedValue(undefined)
      render(<CommentBox onPost={onPost} isPosting={false} postError={null} />)

      write('<p>hello</p>')
      fireEvent.keyDown(editor(), { key: 'Enter', metaKey: true })

      await waitFor(() => expect(onPost).toHaveBeenCalledWith('<p>hello</p>', false))
    })

    /** A plain Enter is a new paragraph. It has to stay one. */
    it('does not post on Enter alone', () => {
      const onPost = vi.fn()
      render(<CommentBox onPost={onPost} isPosting={false} postError={null} />)

      write('<p>hello</p>')
      fireEvent.keyDown(editor(), { key: 'Enter' })

      expect(onPost).not.toHaveBeenCalled()
    })

    it('does not post an empty box on Ctrl+Enter', () => {
      const onPost = vi.fn()
      render(<CommentBox onPost={onPost} isPosting={false} postError={null} />)

      fireEvent.keyDown(editor(), { key: 'Enter', ctrlKey: true })

      expect(onPost).not.toHaveBeenCalled()
    })
  })

  describe('the draft', () => {
    it('clears only once the server has taken it', async () => {
      const onPost = vi.fn().mockResolvedValue(undefined)
      render(<CommentBox onPost={onPost} isPosting={false} postError={null} />)

      write('<p>hello</p>')
      fireEvent.click(screen.getByRole('button', { name: 'Post' }))

      await waitFor(() => expect(editor()).toHaveTextContent(''))
    })

    /**
     * The failure this guards is losing somebody's paragraph on a dropped
     * connection, and a comment is often the longest thing typed on this page.
     * Clearing optimistically and restoring on failure is the version that
     * loses it the one time the restore does not run.
     */
    it('survives a refused post', async () => {
      const onPost = vi.fn().mockRejectedValue(new Error('nope'))
      render(<CommentBox onPost={onPost} isPosting={false} postError={null} />)

      write('<p>a paragraph worth keeping</p>')
      fireEvent.click(screen.getByRole('button', { name: 'Post' }))

      await waitFor(() => expect(onPost).toHaveBeenCalled())
      expect(editor()).toHaveTextContent('a paragraph worth keeping')
    })
  })

  it('renders the server’s refusal as an alert', () => {
    render(
      <CommentBox
        onPost={vi.fn()}
        isPosting={false}
        postError="A comment needs some text. Formatting on its own is not enough."
      />,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('A comment needs some text')
  })

  it('says who can see it, even where there is no toggle to change it', () => {
    render(<CommentBox onPost={vi.fn()} isPosting={false} postError={null} />)

    expect(screen.getByText(/Internal to the team/)).toBeInTheDocument()
  })

  /**
   * C-031 · §4B.5's visibility toggle and §17's "shown in a different colour
   * before posting".
   *
   * The colour assertions read class names, which is normally a brittle way to
   * test a component and is the right one here: the requirement *is* the
   * styling. §17 lists "internal debug notes leak to a client" as a risk whose
   * only mitigation is that the composer looks different before the button is
   * pressed, so a test that checked the flag reached `onPost` and nothing else
   * would pass on a build where the warning had been silently dropped — which
   * is exactly the regression worth catching.
   */
  describe('C-031 · visibility', () => {
    const internal = () => screen.getByRole('radio', { name: /Internal note/ })
    const clientVisible = () => screen.getByRole('radio', { name: /Client visible/ })

    it('opens internal, on a client ticket as much as any other', () => {
      render(
        <CommentBox
          onPost={vi.fn()}
          isPosting={false}
          postError={null}
          clientName="Adityanath Institute"
        />,
      )

      expect(internal()).toBeChecked()
      expect(clientVisible()).not.toBeChecked()
    })

    /**
     * The blueprint's §4B.5 says the default "follows whether the ticket is
     * client-raised"; PLAN.md §5 records the deviation and §16 is the later
     * word. This is the test that keeps the deviation deliberate — the failure
     * it guards is a composer that opens client-visible on precisely the
     * tickets where a leaked internal note costs the most.
     */
    it('never opens client-visible, whatever the ticket is', () => {
      render(
        <CommentBox
          onPost={vi.fn()}
          isPosting={false}
          postError={null}
          clientName="Adityanath Institute"
        />,
      )

      expect(screen.getByText(/Internal to the team/)).toBeInTheDocument()
      expect(screen.queryByText(/will see this comment/)).not.toBeInTheDocument()
    })

    it('offers no toggle at all on a ticket with no client', () => {
      render(<CommentBox onPost={vi.fn()} isPosting={false} postError={null} />)

      expect(screen.queryByRole('radiogroup', { name: 'Visibility' })).not.toBeInTheDocument()
    })

    it('names the client in the warning rather than saying “the client”', () => {
      render(
        <CommentBox
          onPost={vi.fn()}
          isPosting={false}
          postError={null}
          clientName="Adityanath Institute"
        />,
      )

      fireEvent.click(clientVisible())

      expect(
        screen.getByText(/Adityanath Institute will see this comment on the client portal/),
      ).toBeInTheDocument()
    })

    it('restyles the whole composer before anything is posted', () => {
      const { container } = render(
        <CommentBox
          onPost={vi.fn()}
          isPosting={false}
          postError={null}
          clientName="Adityanath Institute"
        />,
      )
      const composer = container.querySelector('section') as HTMLElement

      expect(composer.className).toContain('border-border')

      fireEvent.click(clientVisible())

      // The tint is behind the text as it is typed — not on the control alone,
      // and not only after the post has gone.
      expect(composer.className).toContain('bg-level-high-soft')
      expect(composer.className).toContain('border-warning')
    })

    it('sends the choice with the body', async () => {
      const onPost = vi.fn().mockResolvedValue(undefined)
      render(
        <CommentBox
          onPost={onPost}
          isPosting={false}
          postError={null}
          clientName="Adityanath Institute"
        />,
      )

      fireEvent.click(clientVisible())
      write('<p>Fixed and verified on staging.</p>')
      fireEvent.click(screen.getByRole('button', { name: 'Post' }))

      await waitFor(() =>
        expect(onPost).toHaveBeenCalledWith('<p>Fixed and verified on staging.</p>', true),
      )
    })

    /**
     * The leak this prevents is the *second* comment, not the first: a
     * deliberate client-facing summary followed by a stack trace, with nothing
     * on screen changing in between to prompt a second decision.
     */
    it('returns to internal after a successful post', async () => {
      const onPost = vi.fn().mockResolvedValue(undefined)
      render(
        <CommentBox
          onPost={onPost}
          isPosting={false}
          postError={null}
          clientName="Adityanath Institute"
        />,
      )

      fireEvent.click(clientVisible())
      write('<p>Fixed and verified on staging.</p>')
      fireEvent.click(screen.getByRole('button', { name: 'Post' }))

      await waitFor(() => expect(internal()).toBeChecked())
      expect(screen.getByText(/Internal to the team/)).toBeInTheDocument()
    })

    /**
     * The mirror of the surviving draft: a refusal is about the body, so
     * re-picking the audience for text that did not change would be a second
     * chance to pick it wrong.
     */
    it('keeps the choice when the post is refused', async () => {
      const onPost = vi.fn().mockRejectedValue(new Error('nope'))
      render(
        <CommentBox
          onPost={onPost}
          isPosting={false}
          postError={null}
          clientName="Adityanath Institute"
        />,
      )

      fireEvent.click(clientVisible())
      write('<p>Fixed and verified on staging.</p>')
      fireEvent.click(screen.getByRole('button', { name: 'Post' }))

      await waitFor(() => expect(onPost).toHaveBeenCalled())
      expect(clientVisible()).toBeChecked()
    })

    /**
     * A ticket can lose its client mid-session. The control disappears with it,
     * and without the guard in `clientVisible` the stale `true` would still be
     * sent — a comment marked for an audience the ticket no longer has, chosen
     * by nobody, and invisible on screen.
     */
    it('does not send a stale client-visible flag once the client is gone', async () => {
      const onPost = vi.fn().mockResolvedValue(undefined)
      const { rerender } = render(
        <CommentBox
          onPost={onPost}
          isPosting={false}
          postError={null}
          clientName="Adityanath Institute"
        />,
      )

      fireEvent.click(clientVisible())
      rerender(<CommentBox onPost={onPost} isPosting={false} postError={null} />)

      write('<p>Back to internal.</p>')
      fireEvent.click(screen.getByRole('button', { name: 'Post' }))

      await waitFor(() =>
        expect(onPost).toHaveBeenCalledWith('<p>Back to internal.</p>', false),
      )
      expect(screen.getByText(/Internal to the team/)).toBeInTheDocument()
    })

    it('locks the choice while the post is in flight', () => {
      render(
        <CommentBox
          onPost={vi.fn()}
          isPosting
          postError={null}
          clientName="Adityanath Institute"
        />,
      )

      expect(clientVisible()).toBeDisabled()
    })

    it('offers no toggle on a sealed cycle, where nothing can be posted at all', () => {
      render(
        <CommentBox
          onPost={vi.fn()}
          isPosting={false}
          postError={null}
          disabled
          disabledReason="Cycle 1 is sealed."
          clientName="Adityanath Institute"
        />,
      )

      expect(screen.queryByRole('radiogroup', { name: 'Visibility' })).not.toBeInTheDocument()
    })
  })

  describe('a sealed cycle', () => {
    it('offers no editor at all and says why', () => {
      render(
        <CommentBox
          onPost={vi.fn()}
          isPosting={false}
          postError={null}
          disabled
          disabledReason="Cycle 1 is sealed."
        />,
      )

      expect(screen.queryByRole('textbox', { name: 'Comment' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Post' })).not.toBeInTheDocument()
      expect(screen.getByText('Cycle 1 is sealed.')).toBeInTheDocument()
    })
  })
})
