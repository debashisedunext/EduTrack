import * as React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { useAttachmentPaste } from './use-attachment-paste'

/**
 * C-024. jsdom has no `ClipboardEvent` constructor and no clipboard, so the
 * event is built by hand the same way `attachment-picker.test.tsx` builds a
 * drop — testing-library copies a plain `clipboardData` object onto the event.
 *
 * What is asserted here is the arbitration, which is the whole reason a global
 * listener needed a hook rather than three lines in the picker: which of several
 * mounted pickers takes the paste, and when none of them should.
 */

function fileOf(name: string, size = 1024, type = ''): File {
  const file = new File(['x'], name, { type })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

function clipboardOf(files: File[], text: Partial<Record<string, string>> = {}) {
  return {
    types: [...Object.keys(text), ...(files.length > 0 ? ['Files'] : [])],
    items: [
      ...Object.keys(text).map((kind) => ({ kind: 'string', type: kind, getAsFile: () => null })),
      ...files.map((file) => ({ kind: 'file', type: file.type, getAsFile: () => file })),
    ],
    files,
    getData: (type: string) => text[type] ?? '',
  }
}

function Surface({
  label,
  onFiles,
  enabled = true,
  children,
}: {
  label: string
  onFiles: (files: File[]) => void
  enabled?: boolean
  children?: React.ReactNode
}) {
  useAttachmentPaste({ enabled, onFiles })
  return <div data-testid={label}>{children}</div>
}

describe('useAttachmentPaste', () => {
  it('takes a screenshot pasted anywhere on the page', () => {
    // Nothing ever focuses a drop zone. The agent is typing, or has clicked
    // nowhere at all, which is why the listener is on `document`.
    const onFiles = vi.fn()
    render(<Surface label="s" onFiles={onFiles} />)

    fireEvent.paste(document.body, { clipboardData: clipboardOf([fileOf('image.png', 2048, 'image/png')]) })

    expect(onFiles).toHaveBeenCalledTimes(1)
    // Handed up unnamed. Naming belongs to the picker, which is the only party
    // that knows what is already attached and therefore what a name must avoid.
    expect(onFiles.mock.calls[0][0][0].name).toBe('image.png')
  })

  it('passes every file in a multi-file paste through', () => {
    const onFiles = vi.fn()
    render(<Surface label="s" onFiles={onFiles} />)

    fireEvent.paste(document.body, {
      clipboardData: clipboardOf([fileOf('a.png', 1024, 'image/png'), fileOf('b.png', 1024, 'image/png')]),
    })

    expect(onFiles.mock.calls[0][0]).toHaveLength(2)
  })

  it('does not register at all when it is disabled', () => {
    const onFiles = vi.fn()
    render(<Surface label="s" onFiles={onFiles} enabled={false} />)

    fireEvent.paste(document.body, { clipboardData: clipboardOf([fileOf('image.png', 2048, 'image/png')]) })

    expect(onFiles).not.toHaveBeenCalled()
  })

  it('ignores a paste carrying no files', () => {
    const onFiles = vi.fn()
    render(<Surface label="s" onFiles={onFiles} />)

    fireEvent.paste(document.body, { clipboardData: clipboardOf([], { 'text/plain': 'CRM-26-00347' }) })

    expect(onFiles).not.toHaveBeenCalled()
  })
})

describe('useAttachmentPaste — when it stands down', () => {
  it('leaves an ordinary text paste in a text field alone', () => {
    // Copying an image out of a web page puts the `<img>` tag on the clipboard
    // as `text/html` beside the file, so a file being present is not evidence
    // the user meant to attach anything.
    const onFiles = vi.fn()
    render(
      <Surface label="s" onFiles={onFiles}>
        <input aria-label="Title" />
      </Surface>,
    )

    fireEvent.paste(screen.getByLabelText('Title'), {
      clipboardData: clipboardOf([fileOf('image.png', 1024, 'image/png')], { 'text/html': '<img src="x">' }),
    })

    expect(onFiles).not.toHaveBeenCalled()
  })

  it('still takes an image-only paste made into a text field', () => {
    // Pasting a screenshot into a plain input does nothing at all otherwise,
    // and doing nothing is the failure this task exists to remove.
    const onFiles = vi.fn()
    render(
      <Surface label="s" onFiles={onFiles}>
        <input aria-label="Title" />
      </Surface>,
    )

    fireEvent.paste(screen.getByLabelText('Title'), {
      clipboardData: clipboardOf([fileOf('image.png', 1024, 'image/png')]),
    })

    expect(onFiles).toHaveBeenCalledTimes(1)
  })

  it('leaves a rich-text editor to its own paste handler', () => {
    // `RichTextEditor` intercepts image pastes itself (C-066) and the event
    // bubbles here afterwards. Handling it in both places attaches every pasted
    // screenshot twice; the editor's consumer wires `onPasteFiles` to the
    // picker's `addFiles` handle instead.
    const onFiles = vi.fn()
    render(
      <Surface label="s" onFiles={onFiles}>
        <div contentEditable suppressContentEditableWarning data-testid="editor">
          <p>text</p>
        </div>
      </Surface>,
    )

    fireEvent.paste(screen.getByTestId('editor').querySelector('p')!, {
      clipboardData: clipboardOf([fileOf('image.png', 1024, 'image/png')]),
    })

    expect(onFiles).not.toHaveBeenCalled()
  })
})

describe('useAttachmentPaste — one claimant at a time', () => {
  it('hands the paste to the most recently mounted surface, not both', () => {
    // Ticket detail mounts a picker and quick update mounts a second over it.
    // Both listening means one paste uploads the file twice, against two
    // different requests.
    const behind = vi.fn()
    const inFront = vi.fn()
    render(
      <>
        <Surface label="behind" onFiles={behind} />
        <Surface label="front" onFiles={inFront} />
      </>,
    )

    fireEvent.paste(document.body, { clipboardData: clipboardOf([fileOf('image.png', 1024, 'image/png')]) })

    expect(inFront).toHaveBeenCalledTimes(1)
    expect(behind).not.toHaveBeenCalled()
  })

  it('gives the paste back when the surface in front unmounts', () => {
    // Closing the slide-over has to return the page underneath to working
    // order, or paste is dead on ticket detail for the rest of the session.
    const behind = vi.fn()
    const inFront = vi.fn()
    function Page({ open }: { open: boolean }) {
      return (
        <>
          <Surface label="behind" onFiles={behind} />
          {open && <Surface label="front" onFiles={inFront} />}
        </>
      )
    }

    const { rerender } = render(<Page open />)
    rerender(<Page open={false} />)

    fireEvent.paste(document.body, { clipboardData: clipboardOf([fileOf('image.png', 1024, 'image/png')]) })

    expect(behind).toHaveBeenCalledTimes(1)
    expect(inFront).not.toHaveBeenCalled()
  })

  it('stops listening once every surface has unmounted', () => {
    const onFiles = vi.fn()
    const { unmount } = render(<Surface label="s" onFiles={onFiles} />)
    unmount()

    fireEvent.paste(document.body, { clipboardData: clipboardOf([fileOf('image.png', 1024, 'image/png')]) })

    expect(onFiles).not.toHaveBeenCalled()
  })
})
