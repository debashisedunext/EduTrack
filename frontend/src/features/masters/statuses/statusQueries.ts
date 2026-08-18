import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import {
  getListStatusTransitionsQueryKey,
  getListStatusesQueryKey,
} from '@/api/generated/masters/masters'
import type { Status } from '@/api/generated/model/status'
import type { StatusPatchRequest } from '@/api/generated/model/statusPatchRequest'
import type { StatusTransition } from '@/api/generated/model/statusTransition'
import type { StatusTransitionMatrixWriteRequest } from '@/api/generated/model/statusTransitionMatrixWriteRequest'
import type { StatusWriteRequest } from '@/api/generated/model/statusWriteRequest'

/**
 * B-039 · S-13 tab 1's data layer.
 *
 * The same two things the generated client structurally cannot express, for the
 * reasons `roleQueries.ts`, `calendarQueries.ts`, `taskTypeQueries.ts` and
 * `priorityQueries.ts` give: orval omits header parameters, so `Idempotency-Key`
 * is hand-set, and `http()` drops the response object, so an `ETag` has to come
 * off a plain `fetch`. Delete the hand-written parts the day orval emits header
 * params and a response-aware mutator.
 *
 * **One thing here is new to this stream.** Every other master reads its `ETag`
 * from a single-row route, because the collection is a view over rows edited one
 * at a time. The transition matrix has no single-row route — there is no
 * per-cell verb — so `useStatusTransitions` reads the tag off the *collection*.
 * That is why it is a raw `fetch` too, where every other list here is a plain
 * `http()`.
 */

export const STATUS_KEY = (statusId: number) => ['/masters/statuses', statusId] as const

export interface StatusWithEtag {
  status: Status
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

export interface MatrixWithEtag {
  transitions: StatusTransition[]
  /** Taken over the **whole** matrix, even on a role-filtered read. */
  etag: string | null
}

/**
 * Every status, retired ones included — the grid's list, and **not** the one the
 * ticket screens will use.
 *
 * The two are separated by the query key, which is what keeps them apart in the
 * cache: `getListStatusesQueryKey({ includeInactive: true })` is a different key
 * from the bare `getListStatusesQueryKey()`. Sharing one would put retired
 * statuses into a ticket screen's status filter the moment an admin opened this
 * page — a cache entry written by one screen and read, unfiltered, by another.
 * `priorityQueries.ts` learned this on a route Stream C already consumed; here
 * it is set up before there is a consumer to break.
 */
export function useStatuses() {
  return useQuery<Status[], ApiError>({
    queryKey: getListStatusesQueryKey({ includeInactive: true }),
    queryFn: async ({ signal }) => {
      const body = await http<{ data: Status[] }>({
        url: '/masters/statuses',
        method: 'GET',
        params: { includeInactive: true },
        signal,
      })
      return body.data
    },
  })
}

/**
 * Reads one status **and** its `ETag`.
 *
 * The tag is cached with the data deliberately, as the working week's, the
 * role's, the task type's and the level's are: fetching it separately at submit
 * time would read a value the user never saw, which defeats the guard. The point
 * is to detect that the row changed between the read they edited and the write
 * they sent.
 */
export function useStatus(statusId: number | null) {
  return useQuery<StatusWithEtag, ApiError>({
    queryKey: STATUS_KEY(statusId ?? -1),
    enabled: statusId != null,
    queryFn: async ({ signal }) => {
      const response = await authedFetch(`/masters/statuses/${statusId}`, signal)
      const body = (await response.json()) as { data: Status }
      return { status: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * The whole matrix and its tag.
 *
 * Unfiltered on purpose — the grid renders every role's column, because a cell
 * is only meaningful beside its neighbours, and the `PUT` replaces everything so
 * the screen has to hold everything anyway. The `roleCode` parameter exists on
 * the route for a caller that wants one column; this screen is not it.
 */
export function useStatusTransitions() {
  return useQuery<MatrixWithEtag, ApiError>({
    queryKey: getListStatusTransitionsQueryKey(),
    queryFn: async ({ signal }) => {
      const response = await authedFetch('/masters/status-transitions', signal)
      const body = (await response.json()) as { data: StatusTransition[] }
      return { transitions: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * A save on this screen reaches further than any other master's.
 *
 * Both status list keys go, because a save here changes what a ticket screen's
 * filter offers as well as what this grid shows. **The matrix key goes too, and
 * that one is not obvious**: retiring a status deactivates every transition
 * touching it, so a status write silently changes rows on the other tab.
 * Leaving it stale would show an Admin a matrix with live cells the server has
 * just cleared, and the next save would send them straight back.
 */
function invalidate(queryClient: ReturnType<typeof useQueryClient>, statusId?: number) {
  void queryClient.invalidateQueries({ queryKey: ['/masters/statuses'] })
  void queryClient.invalidateQueries({ queryKey: getListStatusesQueryKey() })
  void queryClient.invalidateQueries({
    queryKey: getListStatusesQueryKey({ includeInactive: true }),
  })
  void queryClient.invalidateQueries({ queryKey: getListStatusTransitionsQueryKey() })
  if (statusId != null) {
    void queryClient.invalidateQueries({ queryKey: STATUS_KEY(statusId) })
  }
}

export function useCreateStatus() {
  const queryClient = useQueryClient()

  return useMutation<Status, ApiError, StatusWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: Status }>({
        url: '/masters/statuses',
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient),
  })
}

/**
 * The one write that edits, and the one that retires — there is no delete.
 *
 * `isActive: false` is how a status goes away, and it is refused while tickets
 * are still in it. Nothing has a foreign key to `statuses`: `tickets.status`
 * holds the code as a string, so a delete would *succeed* and leave every
 * historical ticket rendering a code nothing resolves — and, worse than on the
 * level master, would strand the live ones with no move offered anywhere.
 */
export function useUpdateStatus() {
  const queryClient = useQueryClient()

  return useMutation<
    Status,
    ApiError,
    { statusId: number; data: StatusPatchRequest; etag: string | null }
  >({
    mutationFn: async ({ statusId, data, etag }) => {
      const body = await http<{ data: Status }>({
        url: `/masters/statuses/${statusId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client, which is the failure the
        // whole mechanism exists to prevent.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: (_status, { statusId }) => invalidate(queryClient, statusId),
  })
}

/**
 * Replaces the matrix.
 *
 * `PUT`, and it carries `If-Match` like every other write on this screen —
 * unusual for a collection, and the reason is that this collection is the unit
 * of edit. Two Admins with the tab open would otherwise each save their own
 * screen state, and the second would delete every cell the first had added with
 * nothing to indicate it had happened.
 */
export function useReplaceStatusTransitions() {
  const queryClient = useQueryClient()

  return useMutation<
    StatusTransition[],
    ApiError,
    { data: StatusTransitionMatrixWriteRequest; etag: string | null }
  >({
    mutationFn: async ({ data, etag }) => {
      const body = await http<{ data: StatusTransition[] }>({
        url: '/masters/status-transitions',
        method: 'PUT',
        headers: { 'Idempotency-Key': newIdempotencyKey(), 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient),
  })
}

/**
 * The `ETag` the generated client drops.
 *
 * One helper for both tag-carrying reads rather than two copies, because they
 * differ only in the path — and the day orval emits a response-aware mutator
 * this is the single function that goes.
 */
async function authedFetch(path: string, signal: AbortSignal | undefined) {
  const token = getAccessToken()
  const response = await fetch(`${BASE}${path}`, {
    signal,
    credentials: 'include',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!response.ok) {
    throw new ApiError(
      response.status,
      { type: 'about:blank', title: response.statusText, status: response.status },
      response,
    )
  }
  return response
}
