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

    await waitFor(() => expect(onPost).toHaveBeenCalledWith('<p>Patch pushed, ready for QA.</p>'))
  })

  describe('§4B.5 · Ctrl/Cmd+Enter to post', () => {
    it('posts on Ctrl+Enter', async () => {
      const onPost = vi.fn().mockResolvedValue(undefined)
      render(<CommentBox onPost={onPost} isPosting={false} postError={null} />)

      write('<p>hello</p>')
      fireEvent.keyDown(editor(), { key: 'Enter', ctrlKey: true })

      await waitFor(() => expect(onPost).toHaveBeenCalledWith('<p>hello</p>'))
    })

    /** macOS. A shortcut that only works on Windows reads as broken. */
    it('posts on Cmd+Enter', async () => {
      const onPost = vi.fn().mockResolvedValue(undefined)
      render(<CommentBox onPost={onPost} isPosting={false} postError={null} />)

      write('<p>hello</p>')
      fireEvent.keyDown(editor(), { key: 'Enter', metaKey: true })

      await waitFor(() => expect(onPost).toHaveBeenCalledWith('<p>hello</p>'))
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

  it('says who can see it, because nothing else on screen does until C-031', () => {
    render(<CommentBox onPost={vi.fn()} isPosting={false} postError={null} />)

    expect(screen.getByText(/Internal to the team/)).toBeInTheDocument()
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
