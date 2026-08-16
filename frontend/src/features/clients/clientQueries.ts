import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import { getListClientsQueryKey } from '@/api/generated/clients/clients'
import type { ClientDetail } from '@/api/generated/model/clientDetail'
import type { ClientWriteRequest } from '@/api/generated/model/clientWriteRequest'

/**
 * B-026 · S-33's data layer.
 *
 * The same two things the generated client structurally cannot express, for the
 * reasons `projectQueries.ts`, `roleQueries.ts` and `calendarQueries.ts` all
 * give: orval omits header parameters, so `Idempotency-Key` and `If-Match` are
 * hand-set, and `http()` drops the response object, so the `ETag` the `PATCH`
 * needs has to come off a plain `fetch`.
 *
 * The grid still uses the generated `useListClients` — only the detail read and
 * the two writes are hand-written. Delete the hand-written parts the day orval
 * emits header params and a response-aware mutator.
 */

export const CLIENT_KEY = (clientId: number) => ['/clients', clientId] as const

export interface ClientWithEtag {
  client: ClientDetail
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

/**
 * Reads one client **and** its `ETag`.
 *
 * The tag is cached with the data deliberately, as the project and role masters'
 * are: fetching it separately at submit time would read a value the user never
 * saw, which defeats the guard entirely. The point is to detect that the client
 * changed between the read they edited and the write they sent.
 *
 * Because `contactCount` and `hasPrimaryContact` are part of the tag, a contact
 * added in another tab invalidates it — correctly, since that is the event that
 * makes the client selectable on a ticket.
 */
export function useClient(clientId: number | null) {
  return useQuery<ClientWithEtag, ApiError>({
    queryKey: CLIENT_KEY(clientId ?? -1),
    enabled: clientId != null,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/clients/${clientId}`, {
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
      const body = (await response.json()) as { data: ClientDetail }
      return { client: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * Every list variant, not only the one this screen used.
 *
 * A client saved here has to reappear in the S-32 grid *and* in the ticket
 * create form's client dropdown (§4B.2), which caches its own filtered query
 * under its own key. Invalidating the plain `'/clients'` prefix catches every
 * one of them, which is why the key is an array and the prefix match is worth
 * having — `projectQueries.ts` documents the same shape for the same reason.
 */
function invalidate(queryClient: ReturnType<typeof useQueryClient>, clientId?: number) {
  void queryClient.invalidateQueries({ queryKey: ['/clients'] })
  void queryClient.invalidateQueries({ queryKey: getListClientsQueryKey() })
  if (clientId != null) {
    void queryClient.invalidateQueries({ queryKey: CLIENT_KEY(clientId) })
  }
}

export function useCreateClient() {
  const queryClient = useQueryClient()

  return useMutation<ClientDetail, ApiError, ClientWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: ClientDetail }>({
        url: '/clients',
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient),
  })
}

export function useUpdateClient() {
  const queryClient = useQueryClient()

  return useMutation<
    ClientDetail,
    ApiError,
    { clientId: number; data: ClientWriteRequest; etag: string | null }
  >({
    mutationFn: async ({ clientId, data, etag }) => {
      const body = await http<{ data: ClientDetail }>({
        url: `/clients/${clientId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client, which is the failure the
        // whole mechanism exists to prevent.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: (_client, { clientId }) => invalidate(queryClient, clientId),
  })
}
