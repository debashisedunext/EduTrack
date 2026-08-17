import * as React from 'react'

import type { Comment } from '@/api/generated/model/comment'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { RichTextEditor } from '@/components/ui/rich-text-editor'
import { RichTextView } from '@/components/ui/rich-text-view'
import { RICH_TEXT_COMPACT_TOOLBAR, isRichTextEmpty } from '@/components/ui/rich-text'
import { cn } from '@/lib/utils'

import {
  canDeleteComment,
  canEditComment,
  editMinutesLeft,
  type CommentViewer,
} from './commentPermissions'

/**
 * One comment in the thread — C-033, blueprint §4B.5.
 *
 * Split out of `CommentThread` when the edit window and the tombstone arrived.
 * The thread's job is the list and its states; this one holds a card's own
 * state, and a card now has some: an open editor, a draft, a refusal, and a
 * disclosed original.
 *
 * ## The three things §4B.5 asks for, and where each lives
 *
 * - **"shows an 'edited' marker"** — the marker, and `originalBody` behind a
 *   disclosure. Not a diff: a diff of rich text is a component nobody asked for,
 *   and the blueprint's word is "preserved", which is what showing it does.
 * - **"deletion leaves a tombstone"** — `renderTombstone`. Text, not a card.
 * - **"no role, including Admin, can silently rewrite a comment"** — the Edit
 *   button's absence for everyone but the author, and the tombstone's presence
 *   for every removal including a silent-looking one. Both decided in
 *   `commentPermissions.ts`, both enforced server-side regardless.
 */
export function CommentCard({
  comment,
  viewer,
  onEdit,
  onDelete,
  isEditing,
  editError,
  isRemoving,
  removeError,
}: {
  comment: Comment
  viewer: CommentViewer
  onEdit: (commentId: number, body: string) => Promise<void>
  onDelete: (commentId: number) => Promise<void>
  isEditing: boolean
  editError: string | null
  isRemoving: boolean
  removeError: string | null
}) {
  const [draft, setDraft] = React.useState<string | null>(null)
  const [showOriginal, setShowOriginal] = React.useState(false)
  const editButton = React.useRef<HTMLButtonElement>(null)

  const open = draft !== null

  // Recomputed on every render rather than memoised, and that is the point: the
  // window closes while the page sits idle, with no request and no state change
  // to trigger a recompute. A `useMemo` keyed on the comment would hold the
  // button open for as long as nobody typed. React re-renders this thread often
  // enough — a poll, a post, a hover — that the button disappears within
  // seconds of the deadline, and the server refuses it in between.
  const mayEdit = canEditComment(comment, viewer)
  const mayDelete = canDeleteComment(comment, viewer)
  const minutesLeft = mayEdit ? editMinutesLeft(comment) : null

  const tooLong = false
  const emptyDraft = open && isRichTextEmpty(draft ?? '')

  async function save() {
    if (comment.id == null || draft === null || emptyDraft) return
    try {
      await onEdit(comment.id, draft)
      // Closed only on success. A refusal keeps the editor open with the text
      // still in it — all three refusals are final, and dropping somebody's
      // rewritten paragraph on the way to telling them so loses work they
      // cannot get back.
      setDraft(null)
      // Focus goes back to the control that opened the editor, or it lands on
      // `<body>` and a keyboard user restarts from the top of the page. The
      // button may be gone by now (the window can close mid-edit), in which
      // case there is nothing to restore to and nothing to do.
      editButton.current?.focus()
    } catch {
      // `useTicketComments.edit` rejects so the draft survives; the message is
      // rendered from `editError` below. Swallowed here because an unhandled
      // rejection would reach the console for a refusal already on screen.
    }
  }

  if (comment.isDeleted) {
    return <li>{renderTombstone(comment)}</li>
  }

  return (
    <li
      className={cn(
        'rounded-control border p-3',
        comment.isClientVisible ? 'border-warning bg-surface' : 'border-border bg-subtle',
      )}
    >
      <div className="mb-1 flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span className="text-sm font-medium text-content">
          {/*
            No "Unknown user" fallback. The server resolves an id with no
            matching account to nothing on purpose — a deleted account should
            read as a comment with no author rather than as a name-shaped
            string where the product means it does not know.
          */}
          {comment.author?.displayName ?? 'Author no longer listed'}
        </span>
        {comment.authorRole && (
          <span className="text-caption text-content-muted">{comment.authorRole}</span>
        )}
        {comment.createdAt && (
          <time dateTime={comment.createdAt} className="text-caption text-content-muted">
            {formatPosted(comment.createdAt)}
          </time>
        )}

        {/*
          §4B.5's "edited" marker. Muted and unobtrusive — it is a fact about
          the comment, not a warning about it, and colouring it would put a
          flag on every typo fix. The original sits behind it rather than
          beside it: the current wording is what the thread is about, and a
          reader wanting the earlier one is looking for it deliberately.

          It carries WHEN since D-14, and that is not decoration. While the
          window was five minutes, "edited" implied "moments after posting" and
          a timestamp said nothing. With no limit it implies nothing at all — a
          typo fixed a minute later and a claim rewritten three months on read
          identically, and those are very different facts about a note
          colleagues have already acted on. The date is what makes the marker
          worth having once the clock is gone.
        */}
        {comment.isEdited && !open && (
          <span className="text-caption text-content-muted">
            <span aria-hidden="true">·</span>{' '}
            {comment.editedAt ? (
              <>
                edited{' '}
                <time dateTime={comment.editedAt}>{formatPosted(comment.editedAt)}</time>
              </>
            ) : (
              // A comment edited before D-14 put `editedAt` on the wire. The
              // marker still stands — `isEdited` is the fact — and the bare
              // word is honest where a guessed date would not be.
              'edited'
            )}
          </span>
        )}

        {comment.isClientVisible && (
          <Chip variant="warning">
            <span aria-hidden="true">⚠</span>
            Client visible
          </Chip>
        )}

        {/* Pushed right, so the affordances sit away from the identity line. */}
        <span className="ml-auto flex items-center gap-1">
          {mayEdit && !open && (
            <Button
              ref={editButton}
              variant="ghost"
              size="sm"
              onClick={() => setDraft(comment.body ?? '')}
              // Named, because "Edit" alone in a list of forty is forty
              // identical buttons to a screen-reader user.
              aria-label={`Edit your comment from ${comment.createdAt ? formatPosted(comment.createdAt) : 'this thread'}`}
            >
              Edit
              {minutesLeft != null && (
                <span className="text-caption text-content-muted">
                  {' '}
                  · {minutesLeft} min left
                </span>
              )}
            </Button>
          )}
          {mayDelete && !open && (
            <Button
              variant="ghost"
              size="sm"
              disabled={isRemoving}
              onClick={() => comment.id != null && void onDelete(comment.id)}
              aria-label={`Remove ${comment.author?.displayName ?? 'this'}’s comment`}
            >
              Remove
            </Button>
          )}
        </span>
      </div>

      {open ? (
        <div className="flex flex-col gap-2">
          <RichTextEditor
            value={draft ?? ''}
            onChange={setDraft}
            disabled={isEditing}
            rows={3}
            toolbar={RICH_TEXT_COMPACT_TOOLBAR}
            showCount={tooLong}
            aria-label="Edit comment"
          />
          {editError && (
            // `role="alert"` because it arrives after a button press and there
            // is nothing else on screen to notice.
            <p role="alert" className="text-caption text-danger-text">
              {editError}
            </p>
          )}
          <div className="flex items-center gap-2">
            <Button size="sm" onClick={() => void save()} disabled={isEditing || emptyDraft}>
              {isEditing ? 'Saving…' : 'Save'}
            </Button>
            <Button
              variant="ghost"
              size="sm"
              disabled={isEditing}
              onClick={() => {
                setDraft(null)
                editButton.current?.focus()
              }}
            >
              Cancel
            </Button>
            {/*
              Shown only when a deadline actually exists. Under D-14's default
              there is none, and a line promising a limit that is not enforced
              would be the sort of copy people quote back at you. When a window
              IS configured it is stated rather than implied: somebody opening
              the editor with fifty seconds left needs to know before they start
              rewriting a paragraph, not after the server refuses it.
            */}
            {minutesLeft != null && (
              <span className="text-caption text-content-muted">
                {minutesLeft} min left to edit this comment.
              </span>
            )}
          </div>
        </div>
      ) : (
        <>
          {/*
            Through `RichTextView`, which is the only sanctioned way to render
            stored rich text — §3.9 sanitises on read as well as on write, so
            tightening the allow-list protects rows already in the table. A
            `dangerouslySetInnerHTML` anywhere outside that component fails a
            repo-wide test.
          */}
          <RichTextView html={comment.body ?? ''} emptyText="This comment is empty." />

          {/*
            §4B.5's "with the original preserved". Behind a toggle rather than
            always drawn: an edited comment is common and its original is
            rarely wanted, and rendering both would double the length of every
            corrected paragraph in the thread.

            `originalBody` can be absent on an edited comment — a row edited
            before this task shipped has `edited_at` and no original — so the
            control is tied to the field's presence, not to `isEdited`.
          */}
          {comment.isEdited && comment.originalBody && (
            <div className="mt-2">
              <Button
                variant="ghost"
                size="sm"
                aria-expanded={showOriginal}
                onClick={() => setShowOriginal((was) => !was)}
              >
                {showOriginal ? 'Hide the original' : 'Show what was first posted'}
              </Button>
              {showOriginal && (
                <div className="mt-1 border-l-2 border-border pl-3">
                  <p className="text-caption text-content-muted">Originally posted</p>
                  <RichTextView html={comment.originalBody} emptyText="This comment was empty." />
                </div>
              )}
            </div>
          )}
        </>
      )}

      {removeError && (
        <p role="alert" className="mt-2 text-caption text-danger-text">
          {removeError}
        </p>
      )}
    </li>
  )
}

/**
 * §4B.5's tombstone — "removed by X on date", and nothing else.
 *
 * **Text, not a card.** There is nothing to show: the server clears `body` and
 * `originalBody` both, so a bordered box the height of a real comment would be
 * an empty rectangle in a thread whose job is letting somebody skim what was
 * said. C-028 reached the same shape for the attachment strip and for the same
 * reason.
 *
 * Muted rather than in danger tones. It is a record, not a problem, and
 * colouring it red would put a permanent alarm on every thread where somebody
 * tidied up after themselves.
 *
 * A missing `deletedBy` renders without an actor rather than as "Unknown user" —
 * the server drops an id whose account is gone, which is `CommentUserRefs`' rule
 * throughout, and "removed on 17 Aug" is honest where a name-shaped string is
 * not.
 */
function renderTombstone(comment: Comment) {
  const who = comment.deletedBy?.displayName
  const when = comment.deletedAt ? formatPosted(comment.deletedAt) : null

  return (
    <p className="px-3 py-2 text-caption italic text-content-muted">
      <span aria-hidden="true">🗑 </span>
      {who && when
        ? `Comment removed by ${who} on ${when}`
        : who
          ? `Comment removed by ${who}`
          : when
            ? `Comment removed on ${when}`
            : 'Comment removed'}
    </p>
  )
}

/**
 * The reader's local time, from the UTC the server stores.
 *
 * Conventions put timezone conversion in the presentation layer and nowhere
 * else, which is this. Absolute rather than relative ("3 hours ago"): a thread
 * is read months later as a record of when things happened, and a relative
 * stamp is unreadable the moment it stops being recent.
 */
function formatPosted(iso: string): string {
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return ''
  return at.toLocaleString(undefined, {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
}
