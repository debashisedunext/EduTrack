import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import { getListObImplementationStagesQueryKey } from '@/api/generated/onboarding-masters/onboarding-masters'
import type { ObImplementationStage } from '@/api/generated/model/obImplementationStage'
import type { ObImplementationStageWriteRequest } from '@/api/generated/model/obImplementationStageWriteRequest'

/**
 * OB-15's data layer.
 *
 * The same two things the generated client structurally cannot express, for
 * the reasons `priorityQueries.ts` and its neighbours give: orval omits header
 * parameters, so `Idempotency-Key` is hand-set, and `http()` drops the
 * response object, so the `ETag` the `PATCH` needs as `If-Match` has to come
 * off a plain `fetch`. Delete the hand-written parts the day orval emits
 * header params and a response-aware mutator.
 */

export const IMPLEMENTATION_STAGE_KEY = (stageId: number) =>
  ['/onboarding/implementation-stages', stageId] as const

export interface StageWithEtag {
  stage: ObImplementationStage
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

/**
 * The whole master, retired stages included — which is what OB-15 draws.
 *
 * The bare query key is deliberate and matters more here than it does for most
 * masters: a picker elsewhere asks for `{ isActive: true }` and gets a
 * different cache entry, so opening this screen can never leak a retired stage
 * into somebody's dropdown. `priorityQueries.ts` records the same separation
 * after the opposite mistake.
 */
export function useImplementationStages() {
  return useQuery<ObImplementationStage[], ApiError>({
    queryKey: getListObImplementationStagesQueryKey(),
    queryFn: async ({ signal }) => {
      const body = await http<{ data: ObImplementationStage[] }>({
        url: '/onboarding/implementation-stages',
        method: 'GET',
        signal,
      })
      return body.data
    },
  })
}

/**
 * Reads one stage **and** its `ETag`.
 *
 * The tag is cached with the data deliberately, as every other master's is:
 * fetching it separately at submit time would read a value the user never saw,
 * which defeats the guard. The point is to detect that the row changed between
 * the read they edited and the write they sent — and on this screen the change
 * most worth catching is somebody else's reorder, because it silently changes
 * what the position in the open form means.
 */
export function useImplementationStage(stageId: number | null) {
  return useQuery<StageWithEtag, ApiError>({
    queryKey: IMPLEMENTATION_STAGE_KEY(stageId ?? -1),
    enabled: stageId != null,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/onboarding/implementation-stages/${stageId}`, {
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
      const body = (await response.json()) as { data: ObImplementationStage }
      return { stage: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * Everything, after every write.
 *
 * **Not a targeted invalidation, and not a cache patch from the response
 * body.** A reorder moves rows the response does not describe: inserting at
 * position 2 shifts every stage below it, and each of those rows now has a
 * different `sequence` and therefore a different `ETag`. Patching the list
 * from the one returned row would leave the other five stale — visibly wrong
 * in the position column, and invisibly wrong in the tags, so the *next* edit
 * would 412 for no reason the user can see.
 */
function invalidate(queryClient: ReturnType<typeof useQueryClient>) {
  void queryClient.invalidateQueries({ queryKey: ['/onboarding/implementation-stages'] })
  void queryClient.invalidateQueries({ queryKey: getListObImplementationStagesQueryKey() })
  void queryClient.invalidateQueries({
    queryKey: getListObImplementationStagesQueryKey({ isActive: true }),
  })
}

export function useCreateImplementationStage() {
  const queryClient = useQueryClient()

  return useMutation<ObImplementationStage, ApiError, ObImplementationStageWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: ObImplementationStage }>({
        url: '/onboarding/implementation-stages',
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
 * Rename, reorder, retire — and there is no delete.
 *
 * `isActive: false` is how a stage goes away. Nothing points at these rows
 * today, which is exactly why the restraint has to be built in now rather than
 * added later: the moment a stage is recorded against a journey step or a
 * report filter, a delete becomes a row those references resolve to nothing
 * through, and by then the button already exists.
 */
export function useUpdateImplementationStage() {
  const queryClient = useQueryClient()

  return useMutation<
    ObImplementationStage,
    ApiError,
    { stageId: number; data: ObImplementationStageWriteRequest; etag: string | null }
  >({
    mutationFn: async ({ stageId, data, etag }) => {
      const body = await http<{ data: ObImplementationStage }>({
        url: `/onboarding/implementation-stages/${stageId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client, which is the failure the
        // whole mechanism exists to prevent.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient),
  })
}
