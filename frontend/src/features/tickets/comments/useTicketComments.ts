import { useMutation, useQueryClient } from '@tanstack/react-query'

import { http, newIdempotencyKey, ApiError } from '@/api/http'
import {
  getListCommentsQueryKey,
  useDeleteComment,
  useEditComment,
  useListComments,
} from '@/api/generated/comments/comments'
import type { Comment } from '@/api/generated/model/comment'
import type { CommentResponse } from '@/api/generated/model/commentResponse'
import type { CommentWriteRequest } from '@/api/generated/model/commentWriteRequest'

/**
 * C-029 · the ticket's thread and the one write that adds to it.
 *
 * ## Why this fetches rather than reading the `/full` payload
 *
 * `TicketAttachmentsSection` takes its rows from the aggregated detail call and
 * this deliberately does not, which is worth explaining because the two sit next
 * to each other on the same page.
 *
 * A ticket's attachments are **bounded** — §4B.4 caps them at twenty — so the
 * aggregate can carry all of them and C-023 was right to use it. A thread has no
 * cap at all, and the contract gives `listComments` a cursor and a limit for
 * exactly that reason while `/full` has no way to express either. Putting an
 * unbounded list inside the payload that also has to load before the header
 * renders is how a detail page gets slow on precisely its busiest tickets.
 *
 * It also makes posting cheap: a new comment invalidates this query alone, where
 * reading from the aggregate would mean refetching the ticket, its cycles, its
 * history, its effort logs and its attachments to show one new paragraph.
 *
 * ⚠ **The `/full` payload's `comments` field is a different shape from this
 * one.** `TicketDetailService` projects seven fields (`authorId`, `bodyHtml`,
 * `isInternal`) where the contract's `Comment` declares fifteen (`author`,
 * `body`, `isClientVisible`). The generated TypeScript types `detail.comments`
 * as `Comment[]`, so anything reading it today gets `undefined` for every field
 * it asks for. C-029 does not touch it — that projection is C-019's and the
 * drift predates this task — but **C-034 cannot avoid it**, because interleaving
 * comments into the History tab means reading them from somewhere, and this is
 * the shape that matches the contract.
 */

export interface UseTicketCommentsOptions {
  ticketId: string
  /**
   * Narrows the thread to one cycle, matching the page's `?cycle=`.
   *
   * A sealed cycle's comments stay readable — preserving a cycle is the point of
   * sealing it — so this narrows rather than restricts.
   */
  cycle?: number
}

export interface UseTicketCommentsResult {
  comments: Comment[]
  isLoading: boolean
  /** The thread could not be loaded at all, as opposed to a failed post. */
  loadError: string | null
  post: (body: string, isClientVisible?: boolean) => Promise<void>
  isPosting: boolean
  /** Why the last post was refused, cleared when the next one is attempted. */
  postError: string | null
  /**
   * C-033 · rewrite a comment inside §4B.5's five minutes.
   *
   * Rejects rather than swallowing, because the caller has an open editor and
   * has to decide what to do with the text still in it — see `editError`.
   */
  edit: (commentId: number, body: string) => Promise<void>
  isEditing: boolean
  /**
   * Why the last edit was refused. **The draft is not discarded when this is
   * set**, which is the whole reason `edit` rejects: the three refusals are all
   * final (locked, not yours, gone) and dropping the user's rewritten paragraph
   * on the way to telling them so would lose work they cannot get back.
   */
  editError: string | null
  /** C-033 · tombstone a comment. Every removal leaves a mark; there is no silent case. */
  remove: (commentId: number) => Promise<void>
  isRemoving: boolean
  /**
   * Why the last removal was refused.
   *
   * Carried for the reason `useTicketAttachments.removeError` was added in
   * C-028: a × that can now answer 403 and that silently does nothing invites
   * exactly one response, which is clicking it again.
   */
  removeError: string | null
}

/**
 * Note there is no `readOnly` here, and a sealed cycle is why. Whether a comment
 * may be posted is a question about the surface, not about the data — the thread
 * is read identically either way — so it lives on `CommentBox` as `disabled`.
 * Putting it here would mean the page had to know it before it could fetch, and
 * `isEarlierCycle` is only computable after the detail call has returned.
 */
export function useTicketComments({
  ticketId,
  cycle,
}: UseTicketCommentsOptions): UseTicketCommentsResult {
  const queryClient = useQueryClient()
  const params = cycle == null ? undefined : { cycle }

  const { data, isPending, isError, error } = useListComments(ticketId, params, {
    query: { enabled: ticketId.length > 0, retry: false },
  })

  const mutation = useMutation({
    mutationKey: ['createComment', ticketId],
    mutationFn: ({ data: body, idempotencyKey }: { data: CommentWriteRequest; idempotencyKey: string }) =>
      // Hand-written rather than the generated `useCreateComment`, for the
      // reason `useQuickUpdateMutation` and `createTicketMutation` both give:
      // orval drops header parameters, so the generated hook cannot send
      // `Idempotency-Key`. It matters here — a retried post after a network
      // timeout must not leave the same paragraph on the ticket twice, and a
      // duplicate comment is the kind of thing people delete manually rather
      // than report. Delete this the day orval emits header params.
      http<CommentResponse>({
        url: `/tickets/${ticketId}/comments`,
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        data: body,
      }),
    onSuccess: () => {
      // Every page of the thread, not just the one in view: a cursor page is
      // positional, and invalidating only `params` would leave a cycle-filtered
      // view stale after a post made from the unfiltered one.
      void queryClient.invalidateQueries({ queryKey: [getListCommentsQueryKey(ticketId)[0]] })
    },
  })

  const post = async (body: string, isClientVisible?: boolean) => {
    await mutation.mutateAsync({
      // `isClientVisible` is sent only when it is true. The server defaults it
      // to false and C-031 owns the toggle; sending an explicit `false` from a
      // build with no toggle would mean the request asserts a choice nobody
      // made, and the difference stops being visible in the logs the day the
      // default is revisited.
      data: isClientVisible ? { body, isClientVisible: true } : { body },
      // Minted per attempt at the call site rather than inside `mutationFn`,
      // so react-query's retry of one post reuses one key. See
      // `newIdempotencyKey`'s own note.
      idempotencyKey: newIdempotencyKey(),
    })
  }

  // C-033. Both use the generated mutations rather than `http` directly — unlike
  // the post above, neither sends `Idempotency-Key`, so orval's dropped header
  // parameters cost nothing here. The contract does not put one on either: a
  // replayed PATCH writes the same body a second time and a replayed DELETE hits
  // the idempotent tombstone path, so a retry is already safe without a key.
  const editMutation = useEditComment({
    mutation: {
      onSuccess: () => invalidateThread(),
    },
  })

  const removeMutation = useDeleteComment({
    mutation: {
      onSuccess: () => invalidateThread(),
    },
  })

  function invalidateThread() {
    void queryClient.invalidateQueries({ queryKey: [getListCommentsQueryKey(ticketId)[0]] })
  }

  const edit = async (commentId: number, body: string) => {
    await editMutation.mutateAsync({ ticketId, commentId, data: { body } })
  }

  // The tombstone is not removed from the cache optimistically, and that is
  // deliberate — it is the one mutation here where the row is *supposed* to stay
  // on screen. §4B.5's whole point is that the card becomes "removed by X on
  // date" rather than disappearing, so pulling it out and putting a tombstone
  // back would be two visual changes for one action, and the intermediate state
  // would tell the user the wrong thing about what just happened.
  const remove = async (commentId: number) => {
    await removeMutation.mutateAsync({ ticketId, commentId })
  }

  return {
    comments: data?.data ?? [],
    isLoading: isPending,
    loadError: isError ? messageFor(error, 'This ticket’s comments could not be loaded.') : null,
    post,
    isPosting: mutation.isPending,
    postError: mutation.isError ? messageFor(mutation.error, 'That comment could not be posted.') : null,
    edit,
    isEditing: editMutation.isPending,
    editError: editMutation.isError
      ? messageFor(editMutation.error, 'That edit could not be saved.')
      : null,
    remove,
    isRemoving: removeMutation.isPending,
    removeError: removeMutation.isError
      ? messageFor(removeMutation.error, 'That comment could not be removed.')
      : null,
  }
}

/**
 * The server's own sentence, whenever there is one.
 *
 * `CommentExceptionHandler` writes `detail` for the person in the box — "a
 * comment needs some text", "files cannot be attached to a comment yet" — and
 * those are more useful than anything this file could compose, because the rule
 * they describe lives on the server and has already gone out of step once in
 * this codebase (C-027's hard-coded caps).
 */
function messageFor(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    return error.problem.detail ?? error.problem.title ?? fallback
  }
  return fallback
}
