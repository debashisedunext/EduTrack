import * as React from 'react'

import { findMentionToken, mentionReplacement, type MentionToken } from './mention-token'

/**
 * C-030 · the DOM half of the `@` type-ahead.
 *
 * ## Why this does not touch `RichTextEditor`
 *
 * `components/ui/` is the library all four streams consume, and CLAUDE.md makes
 * changes there **additive only** with a note to the affected streams. A
 * type-ahead needs the caret, the surrounding text and a way to replace a range
 * — three props and a ref on a component three other features render, bought for
 * one consumer. `CommentBox` already wraps the editor in a `<div>` and listens
 * for `keydown` on it, so the events this needs are already bubbling past;
 * everything below reads them from there.
 *
 * The cost of that choice is honest and worth stating: this reaches into the
 * editor's DOM through a ref rather than through an API. It only ever reads the
 * selection and inserts text at it — both operations the browser exposes on any
 * contentEditable — so it depends on the editor being one, not on how it is
 * built. If `RichTextEditor` is ever replaced by a document-model editor
 * (`rich-text-editor.tsx` explains why it is not one today), this is what breaks
 * and this is where the fix goes.
 *
 * ## Insertion goes through `execCommand`, like the editor's own toolbar
 *
 * Replacing the token by writing `textContent` would be invisible to React and
 * to the editor's `onInput`, so the sanitised value the parent holds would drift
 * from what is on screen. `execCommand('insertText')` fires a real `input`
 * event, which runs the editor's own `emit()` — the same path a keystroke takes.
 * It is also what the editor's toolbar uses, so this adds no dependency the
 * component did not already have.
 */

export interface UseMentionTypeaheadResult {
  /** Put this on the element wrapping the editor. */
  containerRef: React.RefObject<HTMLDivElement>
  /** Null when the caret is not inside an `@handle`. */
  token: MentionToken | null
  /** Where to anchor the listbox, in viewport coordinates. */
  anchor: { left: number; top: number } | null
  /** Replaces the token with `@handle ` and closes the list. */
  insert: (handle: string) => void
  /** Closes without inserting — Escape, blur, a post. */
  dismiss: () => void
  /** Re-reads the caret. Call from `input`, `keyup` and `click`. */
  sync: () => void
}

export function useMentionTypeahead({ enabled }: { enabled: boolean }): UseMentionTypeaheadResult {
  const containerRef = React.useRef<HTMLDivElement>(null)
  const [token, setToken] = React.useState<MentionToken | null>(null)
  const [anchor, setAnchor] = React.useState<{ left: number; top: number } | null>(null)
  /**
   * Set when the user dismisses a token with Escape. Cleared as soon as the
   * caret leaves that token, so Escape closes *this* mention rather than
   * switching the feature off for the rest of the draft.
   */
  const dismissedAt = React.useRef<number | null>(null)

  const dismiss = React.useCallback(() => {
    setToken((current) => {
      dismissedAt.current = current?.start ?? null
      return null
    })
    setAnchor(null)
  }, [])

  const sync = React.useCallback(() => {
    if (!enabled) {
      setToken(null)
      setAnchor(null)
      return
    }

    const found = readCaretToken(containerRef.current)
    if (!found) {
      dismissedAt.current = null
      setToken(null)
      setAnchor(null)
      return
    }
    if (dismissedAt.current === found.token.start) {
      return
    }
    dismissedAt.current = null
    setToken(found.token)
    setAnchor(found.anchor)
  }, [enabled])

  const insert = React.useCallback(
    (handle: string) => {
      const selection = window.getSelection()
      const editor = editableOf(containerRef.current)
      if (!selection || !editor || !token) {
        return
      }

      const node = selection.focusNode
      if (!node || node.nodeType !== Node.TEXT_NODE || !editor.contains(node)) {
        return
      }

      // Select the token — from the '@' up to the caret — so the insert
      // replaces it rather than appending after it.
      const range = document.createRange()
      range.setStart(node, token.start)
      range.setEnd(node, Math.min(token.end, node.textContent?.length ?? token.end))
      selection.removeAllRanges()
      selection.addRange(range)

      // The editor is focused already in the keyboard case; not in the click
      // case, where focus is on the option. Without this the insert lands
      // nowhere, which is the whole of the "clicking a name does nothing" bug.
      editor.focus()
      document.execCommand('insertText', false, mentionReplacement(handle))

      dismissedAt.current = null
      setToken(null)
      setAnchor(null)
    },
    [token],
  )

  return { containerRef, token, anchor, insert, dismiss, sync }
}

/**
 * The editable element inside the wrapper.
 *
 * Found by role rather than by a ref the editor does not expose.
 * `rich-text-editor.tsx` renders `role="textbox"` on the contentEditable and
 * calls it load-bearing — *"`textbox` + `aria-multiline` is what makes a
 * contentEditable"* — so this reads a contract the component states rather than
 * a class name it happens to have.
 */
function editableOf(container: HTMLElement | null): HTMLElement | null {
  return container?.querySelector<HTMLElement>('[role="textbox"]') ?? null
}

/**
 * The token at the caret and where to draw the list, or null.
 *
 * Scoped to the caret's own text node. A contentEditable's text is broken across
 * nodes by every bit of formatting, so walking the whole subtree to reconstruct
 * one string would make `@` in a bold run behave differently from `@` in a plain
 * one — and a handle cannot span a formatting boundary anyway, because typing
 * one is a single uninterrupted run of characters.
 */
function readCaretToken(
  container: HTMLElement | null,
): { token: MentionToken; anchor: { left: number; top: number } } | null {
  const editor = editableOf(container)
  const selection = window.getSelection()
  if (!editor || !selection || selection.rangeCount === 0 || !selection.isCollapsed) {
    // A non-collapsed selection is somebody selecting text, not typing a name.
    return null
  }

  const node = selection.focusNode
  if (!node || node.nodeType !== Node.TEXT_NODE || !editor.contains(node)) {
    return null
  }

  const token = findMentionToken(node.textContent ?? '', selection.focusOffset)
  if (!token) {
    return null
  }
  return { token, anchor: anchorFor(node, token, editor) }
}

/**
 * Bottom-left of the `@`, in viewport coordinates.
 *
 * Measured over the token rather than over the collapsed caret: a zero-width
 * range reports a zero rect in more than one browser, and the resulting listbox
 * appears at the top-left of the page. Falling back to the editor's own rect
 * keeps it attached to something visible in the case where even that fails —
 * a misplaced list is recoverable, one drawn at 0,0 over the page header is not.
 */
function anchorFor(
  node: Node,
  token: MentionToken,
  editor: HTMLElement,
): { left: number; top: number } {
  try {
    const range = document.createRange()
    range.setStart(node, token.start)
    range.setEnd(node, Math.min(token.end, node.textContent?.length ?? token.end))
    const rect = range.getBoundingClientRect()
    if (rect.width > 0 || rect.height > 0) {
      return { left: rect.left, top: rect.bottom }
    }
  } catch {
    // Offsets can be stale by a tick if the DOM changed under us. The fallback
    // is correct enough and throwing here would break typing.
  }
  const box = editor.getBoundingClientRect()
  return { left: box.left, top: box.bottom }
}
