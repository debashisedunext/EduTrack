import type { Comment } from '@/api/generated/model/comment'
import { EmptyState } from '@/components/ui/empty-state'
import { RichTextView } from '@/components/ui/rich-text-view'
import { Skeleton } from '@/components/ui/skeleton'

/**
 * The Comments tab's stream — C-029, blueprint §4B.5.
 *
 * Oldest first, which is the one ordering decision here worth arguing about.
 * Every other list in the product is newest-first because it is a work queue;
 * this is a conversation, and the first comment is what gives the rest their
 * context. The server orders it and this does not re-sort — the cursor is
 * keyset over `(createdAt, id)`, so a client-side sort would silently disagree
 * with the paging the moment there is a second page.
 *
 * ## What C-029 does not draw
 *
 * The stamp is **written but not rendered**. `CommentService` copies the ticket's
 * cycle and stage onto every comment, because a stamp is the one field that
 * cannot be backfilled once the ticket has moved on — but drawing "Development ·
 * iteration 2" beside an author is C-032's, and it needs C-042's iteration number
 * to be anything other than misleading. Half a stamp on screen is worse than
 * none: a reader who sees a stage and no iteration has no way to know the
 * iteration is missing rather than one.
 *
 * Also absent, each to its own task: `@mention` highlighting (C-030), the
 * client-visible colour (C-031), the edited marker and tombstones (C-033).
 * `isClientVisible` is rendered as a plain chip in the meantime, because a
 * comment that is going to the client and looks exactly like one that is not is
 * the mistake §4B.5 exists to prevent — the colour is C-031's, the fact is not.
 */
export function CommentThread({
  comments,
  isLoading,
  loadError,
}: {
  comments: Comment[]
  isLoading: boolean
  loadError: string | null
}) {
  if (isLoading) {
    return (
      <div className="flex flex-col gap-3" aria-busy="true">
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-16 w-full" />
      </div>
    )
  }

  if (loadError) {
    return (
      <p role="alert" className="text-caption text-danger-text">
        {loadError}
      </p>
    )
  }

  if (comments.length === 0) {
    return (
      <EmptyState
        title="No comments yet"
        description="The box above posts the first one. Comments are internal to the team unless marked otherwise."
      />
    )
  }

  return (
    // A list, not a stack of divs: a screen-reader user gets "list, 7 items"
    // and can move between them, which is the difference between skimming a
    // thread and reading all of it to find out how long it is.
    //
    // Named, because it is not the only list on this page — the breadcrumb is
    // one too, and "list, 3 items" announced twice with nothing to tell them
    // apart is worse than useless. The name is also what lets a test address
    // the thread rather than whichever `<li>` happens to come first in the DOM.
    <ol aria-label="Comments" className="flex flex-col gap-3">
      {comments.map((comment) => (
        <li key={comment.id} className="rounded-control border border-border bg-surface p-3">
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
            {comment.isClientVisible && (
              <span className="rounded-control bg-subtle px-1.5 py-0.5 text-caption text-content-muted">
                Client visible
              </span>
            )}
          </div>

          {/*
            Through `RichTextView`, which is the only sanctioned way to render
            stored rich text — §3.9 sanitises on read as well as on write, so
            tightening the allow-list protects rows already in the table. A
            `dangerouslySetInnerHTML` anywhere outside that component fails a
            repo-wide test.
          */}
          <RichTextView html={comment.body ?? ''} emptyText="This comment is empty." />
        </li>
      ))}
    </ol>
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
