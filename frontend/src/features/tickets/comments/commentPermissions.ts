import type { Comment } from '@/api/generated/model/comment'
import type { RoleCode } from '@/api/generated/model/roleCode'

/**
 * C-033 · which affordances a card draws, as pure functions over the server's
 * own fields — blueprint §4B.5's immutability row.
 *
 * ## These decide what is *shown*, never what is *allowed*
 *
 * The rules live on the server: `CommentService` refuses an edit with 422 and a
 * deletion with 403, and a DOM manipulated past anything here still gets that
 * refusal. What these do is stop the product offering a button that will be
 * refused, which is a different job with a different failure mode — a missing
 * affordance is a nuisance, a phantom one is a lie.
 *
 * ## Why the window is computed here rather than sent as a boolean
 *
 * `Comment.editableUntil` is a *timestamp*, and the alternative — a `canEdit`
 * flag on the wire — was rejected for a reason specific to this feature: the
 * answer changes while the page is open, with no request in between. A boolean
 * would be stale within seconds of arriving and the card would still show its
 * Edit button four minutes after the window shut. A deadline is the shape of the
 * fact.
 *
 * The deadline itself is the server's, derived from its configured window
 * (`edutrack.comments.edit-window`), so this file holds **no copy of the five
 * minutes**. C-027 is the cautionary tale — its client-side copy of a server cap
 * did not merely disagree with the server, it silently overrode it, and nothing
 * appeared in any log.
 */

export interface CommentViewer {
  /** `null` while `useGetMe()` is still resolving. */
  id: number | null
  role?: RoleCode
}

/**
 * The edit rule: **the author, for as long as the comment exists.**
 *
 * §4B.5 bounded this at five minutes and that limit was lifted — deviation
 * **D-14**, `docs/PLAN.md` §4 — after the window lost to one ordinary case: a
 * developer posts a root-cause note, remembers the missing half thirty minutes
 * later, and can only add a second card reading "correction to the above",
 * leaving the thread with two fragments to reconcile instead of one accurate
 * note.
 *
 * **No role widens it** — not PM, not Admin. The blueprint's sentence is "no
 * role, including Admin, can silently rewrite a comment", and an Admin edit
 * cannot be anything else: however the card were labelled, the thread would
 * still attribute the new wording to the original author. That half of §4B.5 is
 * untouched by D-14, along with the stamp, the marker, the preserved original
 * and the tombstone. `CommentService.edit` enforces the same and
 * `CommentNotEditableException.notTheAuthor` carries the full argument.
 *
 * @param now injected rather than read from the clock, so a test can stand
 *            either side of a configured deadline without waiting — the same
 *            reason `CommentService` takes a `Clock`
 */
export function canEditComment(
  comment: Comment,
  viewer: CommentViewer,
  now: Date = new Date(),
): boolean {
  if (comment.isDeleted) return false
  if (viewer.id == null || comment.author?.id !== viewer.id) return false

  // ⚠ A null `editableUntil` means THERE IS NO DEADLINE, not "cannot edit".
  //
  // This line read `return false` until D-14, and was right while the server
  // always sent a deadline: a missing bound then meant a tombstone or a row
  // that could not be placed in time, and refusing was the safe direction. With
  // no window configured the server sends null for *every* comment, so the same
  // line would hide the Edit button across the entire product — while every
  // request it declined to make would have succeeded. The tombstone case it was
  // really covering is handled above, by `isDeleted`.
  if (!comment.editableUntil) return true

  const deadline = Date.parse(comment.editableUntil)
  // An unparseable deadline is treated as no deadline rather than an expired
  // one. The server decides either way, and refusing here would let a malformed
  // field silently remove a capability the caller actually has.
  if (Number.isNaN(deadline)) return true

  // Inclusive, matching `CommentService.withinEditWindow`. A user clicking Save
  // as the countdown reaches zero should not lose the edit to a millisecond, and
  // the server is the one that decides in any case.
  return now.getTime() <= deadline
}

/**
 * §4B.5's deletion rule as C-033 resolved it: the author, a PM or an Admin.
 *
 * **No window.** §4B.5 attaches its five minutes to editing and says of deletion
 * only that it leaves a tombstone — so unlike C-028's attachments there is no
 * silent branch and no expiry, and an author may still remove a comment from
 * last year. What changes with time is nothing; what is constant is that the
 * removal is recorded.
 *
 * PM and Admin are here for C-028's reason: a comment posted client-visible by
 * mistake is a disclosure, and waiting for its author to come back from leave is
 * not a remedy.
 */
export function canDeleteComment(comment: Comment, viewer: CommentViewer): boolean {
  if (comment.isDeleted) return false
  if (viewer.id == null) return false
  if (comment.author?.id === viewer.id) return true
  return viewer.role === 'ADMIN' || viewer.role === 'PM'
}

/**
 * Whole minutes left in the window, or `null` when there is no live one.
 *
 * **Returns null for every comment on the default configuration**, because
 * D-14 leaves no window to count down — the card simply offers Edit with no
 * timer beside it, which is the honest rendering of "for as long as it exists".
 * The function survives because the window does: restoring
 * `edutrack.comments.edit-window` brings the countdown back with it.
 *
 * Minutes and not seconds, deliberately. A second-by-second countdown on a
 * comment somebody is reading is a pressure device, and the precision is false
 * anyway — the request has to reach the server, which applies its own clock.
 * Rounded **up**, so "1 minute left" covers the last 60 seconds rather than
 * reading "0 minutes left" for a window still open.
 */
export function editMinutesLeft(comment: Comment, now: Date = new Date()): number | null {
  if (!comment.editableUntil) return null
  const deadline = Date.parse(comment.editableUntil)
  if (Number.isNaN(deadline)) return null
  const remaining = deadline - now.getTime()
  if (remaining <= 0) return null
  return Math.ceil(remaining / 60_000)
}
