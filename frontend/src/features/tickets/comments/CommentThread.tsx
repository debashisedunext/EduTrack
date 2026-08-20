import type { Comment } from '@/api/generated/model/comment'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

import { CommentCard } from './CommentCard'
import type { CommentViewer } from './commentPermissions'

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
 * ## What this still does not draw
 *
 * `@mention` highlighting inside a rendered body — C-030 resolves the mentions
 * and the type-ahead composes them, but colouring a resolved `@handle` at rest
 * is a change to `CommentCard`'s body rendering with its own review, and is
 * left for whoever picks it up next.
 *
 * The stage-and-iteration stamp itself is drawn as of C-032: `CommentCard`
 * shows "Development · iteration 2" beside the author, once `iterationNo` is
 * something other than misleading — C-042's `TicketJournal#openHopFor` is
 * what made it readable at all.
 *
 * ## C-033 · a card now has state, so a card is now a component
 *
 * The edited marker, the disclosed original, the inline editor and the tombstone
 * all live in `CommentCard`. This file keeps what it always had — the list, its
 * loading, error and empty states, and the ordering argument above — because a
 * card's open editor and a card's refusal are per-card facts and hoisting them
 * here would mean keying two maps by comment id to say what one `useState` says.
 *
 * ## C-031 · the two backgrounds
 *
 * §4B.5 specifies the pair literally — "**Internal note** (grey background, team
 * only) or **Client visible** (white, appears on the client portal and in the
 * client email thread)" — and this reads them in that order rather than tinting
 * only the exception. The internal card was white before this task, which meant
 * the grey/white distinction the blueprint relies on did not exist at all: every
 * comment looked like the safe kind.
 *
 * White alone is a weak signal for the *un*safe kind, though, so the
 * client-visible card carries an amber border and an amber chip as well. That
 * is not a deviation from §4B.5 so much as §12.1's rule applied to it — colour
 * is never the only signal — and it matches what the composer shows before
 * posting, so a comment looks in the thread the way it looked when it was
 * written. Somebody scanning for "what did we actually tell them" finds it
 * without reading every card.
 */
export function CommentThread({
  comments,
  isLoading,
  loadError,
  viewer,
  onEdit,
  onDelete,
  isEditing,
  editError,
  isRemoving,
  removeError,
}: {
  comments: Comment[]
  isLoading: boolean
  loadError: string | null
  /** C-033 · who is reading, which decides which cards offer Edit and Remove. */
  viewer: CommentViewer
  onEdit: (commentId: number, body: string) => Promise<void>
  onDelete: (commentId: number) => Promise<void>
  isEditing: boolean
  editError: string | null
  isRemoving: boolean
  removeError: string | null
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
        <CommentCard
          key={comment.id}
          comment={comment}
          viewer={viewer}
          onEdit={onEdit}
          onDelete={onDelete}
          isEditing={isEditing}
          editError={editError}
          isRemoving={isRemoving}
          removeError={removeError}
        />
      ))}
    </ol>
  )
}
