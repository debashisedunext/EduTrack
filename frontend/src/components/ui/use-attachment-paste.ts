import * as React from 'react'
import { clipboardHasText, filesFromClipboard } from './attachments'

/**
 * Clipboard paste for attachments — C-024, blueprint §4B.4.
 *
 * §4B.4 is right that paste is what decides whether support agents use
 * attachments at all: the most common attachment on the product is a Snipping
 * Tool capture, and that capture only exists on the clipboard. There is no file
 * on disk to drag and nothing to browse to.
 *
 * ## Why the listener is on `document`
 *
 * A paste event fires at whatever has focus. Nothing ever focuses a drop zone —
 * the agent is typing in the description, or has clicked nowhere at all — so a
 * listener on the picker's own subtree would catch almost no real paste. The
 * whole screen has to be the target, which is what every product that gets this
 * right does.
 *
 * That makes the listener global, and global listeners have to answer two
 * questions this hook exists to answer: *which* picker gets the files, and when
 * to keep its hands off an ordinary paste.
 *
 * ## One claimant at a time
 *
 * Ticket detail mounts a picker, and opening quick update mounts a second one
 * over it. Both listening means one paste attaches the file twice, to two
 * different requests. Registrations are therefore a stack and **only the
 * top — the most recently mounted — handles the event.** For a slide-over, a
 * modal or a dialog over a page, most-recently-mounted is the one in front,
 * which is the one the user is looking at.
 *
 * ## When it declines
 *
 * **A rich-text editor is left alone.** `RichTextEditor` intercepts paste
 * itself and routes image files out through `onPasteFiles` (C-066), and a paste
 * inside it bubbles here afterwards — handling it in both places attaches every
 * pasted screenshot twice. The editor's own consumer wires `onPasteFiles` to the
 * picker's `addFiles` handle, so nothing is lost by standing down.
 *
 * **An ordinary text paste is left alone.** Copying an image from a web page
 * puts an `<img>` tag on the clipboard as `text/html` beside the file, so a file
 * being present is not evidence the user meant to attach it. If focus is in an
 * `input` or `textarea` and there is real text on the clipboard, the text wins.
 * An image-only clipboard is taken even from a text field — pasting a screenshot
 * into a plain input does nothing at all otherwise, and doing nothing is the
 * failure this task exists to remove.
 */

export interface UseAttachmentPasteOptions {
  /** Registers only while true. A disabled or read-only surface passes false. */
  enabled?: boolean
  /** Already renamed by `filesFromClipboard`; still unvalidated. */
  onFiles: (files: File[]) => void
}

/**
 * Mounted paste claimants, innermost last.
 *
 * Module-level because the whole point is coordination *between* independently
 * mounted pickers, which have no other channel to each other. React context
 * would not do it: quick update's slide-over renders in a portal and shares no
 * provider with the detail page's strip.
 */
const claimants: symbol[] = []

/** A contentEditable owns its own paste — see the header. */
function isRichTextTarget(target: EventTarget | null): boolean {
  const el = target as Element | null
  return typeof el?.closest === 'function' && el.closest('[contenteditable=""],[contenteditable="true"]') !== null
}

/** A field a paste of *text* would land in. `type=file` and the buttons are not. */
const NON_TEXT_INPUT_TYPES = new Set(['file', 'checkbox', 'radio', 'button', 'submit', 'reset', 'image', 'range', 'color'])

function isPlainTextEntry(target: EventTarget | null): boolean {
  const el = target as Element | null
  if (typeof el?.closest !== 'function') return false
  const field = el.closest('input,textarea')
  if (!field) return false
  if (field.tagName === 'TEXTAREA') return true
  return !NON_TEXT_INPUT_TYPES.has((field as HTMLInputElement).type)
}

export function useAttachmentPaste({ enabled = true, onFiles }: UseAttachmentPasteOptions): void {
  // Held in a ref so a new `onFiles` identity on every parent render does not
  // tear the listener down and re-register it — which would also reshuffle the
  // claimant stack and hand the paste to the wrong picker.
  const onFilesRef = React.useRef(onFiles)
  onFilesRef.current = onFiles

  React.useEffect(() => {
    if (!enabled) return

    const token = Symbol('attachment-paste')
    claimants.push(token)

    const handlePaste = (event: ClipboardEvent) => {
      if (claimants[claimants.length - 1] !== token) return
      if (isRichTextTarget(event.target)) return

      const files = filesFromClipboard(event.clipboardData)
      if (files.length === 0) return
      if (isPlainTextEntry(event.target) && clipboardHasText(event.clipboardData)) return

      // Only once we know we are taking the files. Calling it earlier would
      // swallow a text paste we then decline to act on.
      event.preventDefault()
      onFilesRef.current(files)
    }

    document.addEventListener('paste', handlePaste)
    return () => {
      document.removeEventListener('paste', handlePaste)
      const at = claimants.lastIndexOf(token)
      if (at >= 0) claimants.splice(at, 1)
    }
  }, [enabled])
}
