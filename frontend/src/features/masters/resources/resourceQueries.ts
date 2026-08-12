import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import http, { ApiError, BASE, getAccessToken } from '@/api/http'
import { getListUsersQueryKey } from '@/api/generated/users/users'
import type { UserDetail } from '@/api/generated/model/userDetail'
import type { UserWriteRequest } from '@/api/generated/model/userWriteRequest'

/**
 * B-011 · the S-08 form's data layer.
 *
 * Not the generated `useGetUser` / `useUpdateUser`, and for the two reasons
 * `calendarQueries.ts` already documents — the generated client structurally
 * cannot express either:
 *
 * 1. **The `ETag`.** `http()` returns a parsed body and drops the response,
 *    which is right for every other call. Here the optimistic-concurrency guard
 *    *is* the header, and `PATCH /users/{id}` answers `428` without it.
 * 2. **Header parameters.** Orval omits them, so neither `If-Match` nor
 *    `Idempotency-Key` appears in the generated signature. C-010 worked around
 *    the same gap in `createTicketMutation.ts`.
 *
 * Delete the hand-written parts the day orval emits header params and a
 * response-aware mutator; the call sites keep working.
 */

export function resourceKey(userId: number) {
  return ['/users', userId] as const
}

export interface ResourceWithEtag {
  resource: UserDetail
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

/**
 * Reads one resource **and** its `ETag`.
 *
 * The tag is cached with the data deliberately. Fetching it separately at
 * submit time would read a value the user never saw, which defeats the guard
 * entirely: the point is to detect that the row changed between the read they
 * edited and the write they sent.
 */
export function useResource(userId: number | null) {
  return useQuery<ResourceWithEtag, ApiError>({
    queryKey: resourceKey(userId ?? 0),
    enabled: userId != null,
    // The form seeds itself from this once. Refetching underneath somebody
    // mid-edit would either discard their typing or silently move the ETag out
    // from under the save they are about to make.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/users/${userId}`, {
        signal,
        credentials: 'include',
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })
      if (!response.ok) {
        let problem = { type: 'about:blank', title: response.statusText, status: response.status }
        try {
          problem = { ...problem, ...(await response.json()) }
        } catch {
          // A 404 from a proxy has no body. The status is the whole message.
        }
        throw new ApiError(response.status, problem, response)
      }
      const body = (await response.json()) as { data: UserDetail }
      return { resource: body.data, etag: response.headers.get('ETag') }
    },
  })
}

export interface CreatedResource {
  resource: UserDetail
  /**
   * Readable exactly once, in this response. It is stored as an Argon2id hash,
   * so nothing can retrieve it later — an admin who closes the dialog without
   * copying it issues a reset.
   */
  temporaryPassword: string
}

export function useCreateResource() {
  const queryClient = useQueryClient()

  return useMutation<CreatedResource, ApiError, { data: UserWriteRequest; idempotencyKey: string }>({
    mutationFn: async ({ data, idempotencyKey }) => {
      const body = await http<{ data: UserDetail; meta: { temporaryPassword: string } }>({
        url: '/users',
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        data,
      })
      return { resource: body.data, temporaryPassword: body.meta.temporaryPassword }
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: getListUsersQueryKey() })
    },
  })
}

export function useUpdateResource() {
  const queryClient = useQueryClient()

  return useMutation<
    ResourceWithEtag,
    ApiError,
    { userId: number; data: UserWriteRequest; etag: string | null }
  >({
    mutationFn: async ({ userId, data, etag }) => {
      const body = await http<{ data: UserDetail }>({
        url: `/users/${userId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client, which is the failure the
        // whole mechanism exists to prevent.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      // The response's own ETag is not readable through `http()`, so the cache
      // is invalidated rather than written through. A second save without a
      // reload would otherwise send the tag from before the first one and get a
      // 412 for a conflict with itself.
      return { resource: body.data, etag: null }
    },
    onSuccess: (_result, { userId }) => {
      void queryClient.invalidateQueries({ queryKey: resourceKey(userId) })
      void queryClient.invalidateQueries({ queryKey: getListUsersQueryKey() })
    },
  })
}
