import * as React from 'react'

import { Button } from '@/components/ui/button'
import { RichTextEditor } from '@/components/ui/rich-text-editor'
import {
  RICH_TEXT_COMPACT_TOOLBAR,
  RICH_TEXT_MAX_LENGTH,
  isRichTextEmpty,
} from '@/components/ui/rich-text'

/**
 * S-20's comment box — C-029, blueprint §4B.5.
 *
 * Sits directly under the description and the attachment strip and **above the
 * tabs**, where §7's S-20 wireframe puts it. §7 says why it lives outside the
 * tab strip rather than inside the Comments tab: "the comment box itself is
 * always visible above the tabs so posting never costs a click". Somebody who
 * has just read the description and wants to answer it should not have to find
 * a tab first.
 *
 * ## What this is not
 *
 * Not the Comments tab — that is `CommentThread`, which renders the stream below
 * it. Not the `@mention` type-ahead (C-030), not the visibility toggle (C-031),
 * not the five-minute edit window (C-033). Every comment posted from here is
 * **internal**, which is the default the server applies and the one §16 settled
 * on: "an accidental leak is far costlier than an extra click."
 *
 * That last point is the reason this component takes no `isClientVisible` prop
 * at all rather than one defaulting to false. A prop would be a seam somebody
 * could pass `true` through before C-031 draws the colour that makes the choice
 * visible, and a client-visible comment that looks exactly like an internal one
 * is the failure §4B.5 is trying to prevent.
 */
export function CommentBox({
  onPost,
  isPosting,
  postError,
  disabled = false,
  disabledReason,
}: {
  /** Resolves when the server has accepted it; rejects and the draft survives. */
  onPost: (body: string) => Promise<void>
  isPosting: boolean
  /** The server's sentence, rendered verbatim. */
  postError: string | null
  disabled?: boolean
  disabledReason?: string
}) {
  const [body, setBody] = React.useState('')

  // Not `body.trim()`. A contentEditable is never truly empty — the browser
  // leaves `<p><br></p>` behind, which is thirteen characters of nothing — and
  // `isRichTextEmpty` is the shared check that knows it. It also counts an image
  // with no text as non-empty, which matters here: a pasted screenshot is
  // frequently the whole of what someone wants to say.
  const empty = isRichTextEmpty(body)
  const tooLong = body.length > RICH_TEXT_MAX_LENGTH
  const canPost = !empty && !tooLong && !isPosting && !disabled

  const submit = React.useCallback(async () => {
    if (!canPost) return
    const draft = body
    try {
      await onPost(draft)
      // Cleared only after the server has taken it. Clearing optimistically and
      // restoring on failure is the version that loses somebody's paragraph the
      // one time the network drops, and a comment is often the longest thing
      // anyone types on this page.
      setBody('')
    } catch {
      // The message comes from `postError`; the draft stays exactly as it was so
      // the fix is to edit and press again.
    }
  }, [body, canPost, onPost])

  /**
   * §4B.5: "Ctrl/Cmd + Enter to post".
   *
   * On the wrapper rather than the editor because `RichTextEditor` exposes no
   * `onKeyDown` — the event bubbles out of the contentEditable, which is enough,
   * and widening that shared component's props for one consumer is the kind of
   * change every other stream would have to absorb.
   *
   * `metaKey` as well as `ctrlKey`: Cmd is the modifier on macOS and a shortcut
   * that only works on Windows reads as broken rather than as absent.
   */
  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key !== 'Enter' || !(event.ctrlKey || event.metaKey)) return
    event.preventDefault()
    void submit()
  }

  const hintId = React.useId()

  return (
    <section
      aria-labelledby="ticket-comment-box-heading"
      className="rounded-card border border-border bg-surface p-4 shadow-rest"
    >
      <h2 id="ticket-comment-box-heading" className="mb-2 text-h3 text-content">
        Add a comment
      </h2>

      {disabled ? (
        <p className="text-caption text-content-muted">
          {disabledReason ?? 'Comments cannot be added here.'}
        </p>
      ) : (
        <div className="flex flex-col gap-2" onKeyDown={onKeyDown}>
          <RichTextEditor
            value={body}
            onChange={setBody}
            disabled={isPosting}
            rows={3}
            // The short bar, built for this box by C-066: a comment is a
            // paragraph or two, and offering headings there invites people to
            // shout.
            toolbar={RICH_TEXT_COMPACT_TOOLBAR}
            placeholder="Write a comment…"
            showCount={tooLong}
            aria-label="Comment"
            aria-describedby={hintId}
            aria-invalid={tooLong || undefined}
          />

          {postError && (
            // `role="alert"` because the refusal arrives after a button press
            // with nothing else on screen changing — without it a screen-reader
            // user gets silence and a draft that did not clear.
            <p role="alert" className="text-caption text-danger-text">
              {postError}
            </p>
          )}

          <div className="flex items-center justify-between gap-3">
            <p id={hintId} className="text-caption text-content-muted">
              {/*
                Stated rather than left to be discovered. It is the difference
                between the two-click daily driver §4B.5 asks for and a box
                people reach for the mouse to send, and a keyboard shortcut
                nobody is told about is one nobody uses.

                "Internal" is stated for a different reason: until C-031 draws
                the toggle, this is the only thing on screen saying who can see
                what is being written, and someone about to paste a client's
                own words deserves to know it is not going back to them.
              */}
              Internal to the team. Ctrl+Enter to post.
            </p>
            <Button size="sm" onClick={() => void submit()} disabled={!canPost}>
              {isPosting ? 'Posting…' : 'Post'}
            </Button>
          </div>
        </div>
      )}
    </section>
  )
}
