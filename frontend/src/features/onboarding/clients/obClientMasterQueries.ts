import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import type { ObClient } from '@/api/generated/model/obClient'
import type { ObClientDetail } from '@/api/generated/model/obClientDetail'
import type { ObClientCreateRequest } from '@/api/generated/model/obClientCreateRequest'
import type { ObClientCreateMeta } from '@/api/generated/model/obClientCreateMeta'
import type { ObPortalLoginIssued } from '@/api/generated/model/obPortalLoginIssued'
import type { ObClientUpdateRequest } from '@/api/generated/model/obClientUpdateRequest'
import type { Meta } from '@/api/generated/model/meta'
import { invalidateObDashboard } from '@/features/onboarding/dashboard/obDashboardFreshness'

/**
 * The data layer for the Clients master — list, add, edit, delete.
 *
 * <h2>Hand-written for the two things orval cannot express</h2>
 *
 * The same pair every onboarding master works around, for the reasons
 * `productQueries.ts` gives: header parameters are omitted from the generated
 * client, so `Idempotency-Key` on the create and `If-Match` on the edit are set
 * here; and `http()` drops the response object, so the `ETag` the `PATCH`
 * requires has to come off a plain `fetch`. Delete the hand-written halves the
 * day orval emits header params and a response-aware mutator.
 *
 * <h2>The delete has no `If-Match`, and that is not an omission</h2>
 *
 * A precondition protects a lost update — two people editing one record, the
 * second overwriting the first unseen. A delete has no such failure: the
 * server's guard re-asks, inside the transaction, whether anything now depends
 * on the client, so a project created a second ago refuses the delete whatever
 * tag this screen is holding. Requiring one would add a round trip and protect
 * nothing.
 */

/** Every query under `/onboarding/clients` — one invalidation reaches the list and every detail. */
const CLIENTS_PREFIX = ['/onboarding/clients'] as const

export const CLIENT_KEY = (obClientId: number) => ['/onboarding/clients', obClientId] as const

export interface ClientPage {
  data: ObClient[]
  meta?: Meta
}

export interface ClientWithEtag {
  client: ObClientDetail
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

/**
 * One page of clients.
 *
 * `q` matches the name or the code — both are on the server's filter, and the
 * master's search box is the only place either is typed.
 *
 * <p>The page on screen is kept while the next one is fetched, for the reason
 * `useObProjects` gives: dropping to the skeleton mid-page removes the pager
 * from under the cursor of the person still clicking Next. It also stops the
 * list flickering to grey on every keystroke in the search box, which is the
 * same query changing.
 */
export function useObClients(params: { q?: string; cursor?: string; limit?: number }) {
  const search = new URLSearchParams()
  if (params.q?.trim()) search.set('q', params.q.trim())
  if (params.cursor) search.set('cursor', params.cursor)
  if (params.limit) search.set('limit', String(params.limit))
  const query = search.toString()

  return useQuery<ClientPage, ApiError>({
    queryKey: [...CLIENTS_PREFIX, 'list', query],
    queryFn: ({ signal }) =>
      http<ClientPage>({
        url: `/onboarding/clients${query ? `?${query}` : ''}`,
        method: 'GET',
        signal,
      }),
    placeholderData: keepPreviousData,
  })
}

/**
 * One client **and** its `ETag`, for the edit dialog.
 *
 * The tag is cached with the data, as every other master's is: fetching it at
 * submit time would read a value the user never saw, which defeats the guard.
 * It covers the whole client document, journeys included, so a step transition
 * elsewhere costs a reload — strict, deliberately, and the server's own choice
 * rather than this layer's.
 */
export function useObClient(obClientId: number | null) {
  return useQuery<ClientWithEtag, ApiError>({
    queryKey: CLIENT_KEY(obClientId ?? -1),
    enabled: obClientId != null,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/onboarding/clients/${obClientId}`, {
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
      const body = (await response.json()) as { data: ObClientDetail }
      return { client: body.data, etag: response.headers.get('ETag') }
    },
  })
}

function invalidate(queryClient: ReturnType<typeof useQueryClient>) {
  void queryClient.invalidateQueries({ queryKey: CLIENTS_PREFIX })
  // A client's name and code are printed on every project row, so the grid is
  // stale the moment either changes. Cheaper to invalidate both lists than to
  // leave somebody looking at a name they have just corrected.
  void queryClient.invalidateQueries({ queryKey: ['/onboarding/projects'] })
  // The RAG board is three pages of this same list and the counters count the
  // same clients, so OB-02 is stale for exactly the changes this list is.
  invalidateObDashboard(queryClient)
}

/**
 * The client, and the portal login if one was asked for.
 *
 * `meta` is absent unless `createPortalLogin` was true, and its `password` is
 * null off a development build — there the client is mailed a one-time link
 * and there is no password for anyone to read.
 */
export interface CreatedObClient {
  client: ObClientDetail
  portalLogin: ObPortalLoginIssued | null
}

export function useCreateObClient() {
  const queryClient = useQueryClient()

  return useMutation<CreatedObClient, ApiError, ObClientCreateRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: ObClientDetail; meta?: ObClientCreateMeta }>({
        url: '/onboarding/clients',
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return { client: body.data, portalLogin: body.meta?.portalLogin ?? null }
    },
    onSuccess: () => invalidate(queryClient),
  })
}

export function useUpdateObClient() {
  const queryClient = useQueryClient()

  return useMutation<
    ObClientDetail,
    ApiError,
    { obClientId: number; data: ObClientUpdateRequest; etag: string | null }
  >({
    mutationFn: async ({ obClientId, data, etag }) => {
      const body = await http<{ data: ObClientDetail }>({
        url: `/onboarding/clients/${obClientId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient),
  })
}

/**
 * Delete a client nothing depends on.
 *
 * Answers `409 ob-client-in-use` with a `blockers` array when something does —
 * projects, a prerequisite checklist, uploaded documents or a portal login. The
 * screen reads that array rather than the sentence, because which blocker it is
 * decides what to offer instead.
 */
export function useDeleteObClient() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, number>({
    mutationFn: async (obClientId) => {
      await http<void>({ url: `/onboarding/clients/${obClientId}`, method: 'DELETE' })
    },
    onSuccess: () => invalidate(queryClient),
  })
}

/** The `blockers` an `ob-client-in-use` problem carries, or an empty list. */
export function blockersFrom(error: ApiError): string[] {
  const problem = error.problem as { blockers?: unknown }
  return Array.isArray(problem.blockers) ? problem.blockers.map(String) : []
}
