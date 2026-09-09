import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import http, { ApiError, BASE, getAccessToken } from '@/api/http'
import type { ObSettings } from '@/api/generated/model/obSettings'
import type { ObSettingsWriteRequest } from '@/api/generated/model/obSettingsWriteRequest'

/**
 * B-113 · OB-11's data layer.
 *
 * The same two things the generated client structurally cannot express, for the
 * reason `clientQueries.ts`, `projectQueries.ts`, `roleQueries.ts` and
 * `calendarQueries.ts` all give: orval omits header parameters, so `If-Match`
 * is hand-set, and `http()` drops the response object, so the `ETag` the `PUT`
 * needs has to come off a plain `fetch`.
 *
 * This matters more here than on most screens. `updateObSettings` is a
 * wholesale replace behind one Save button, so a lost update discards the
 * other admin's whole escalation matrix along with the threshold they did not
 * touch — which is exactly why the contract makes `If-Match` mandatory rather
 * than optional. A generated client that silently omitted it would turn a
 * mandatory precondition into a 428 on every save.
 *
 * Delete this file the day orval emits header params and a response-aware
 * mutator.
 */

export const OB_SETTINGS_KEY = ['/onboarding/settings'] as const

export interface ObSettingsWithEtag {
  settings: ObSettings
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

/**
 * Reads the settings **and** their `ETag`.
 *
 * The tag is cached with the data deliberately, as the client and project
 * masters' are: fetching it separately at submit time would read a value the
 * admin never saw, which defeats the guard entirely. The point is to detect
 * that the settings changed between the read they edited and the write they
 * sent.
 */
export function useObSettings() {
  return useQuery<ObSettingsWithEtag, ApiError>({
    queryKey: OB_SETTINGS_KEY,
    queryFn: async () => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/onboarding/settings`, {
        headers: token ? { Authorization: `Bearer ${token}` } : undefined,
      })
      if (!response.ok) {
        throw new ApiError(response.status, await response.json().catch(() => ({})), response)
      }
      const body = (await response.json()) as { data: ObSettings }
      return { settings: body.data, etag: response.headers.get('ETag') }
    },
  })
}

export function useUpdateObSettings() {
  const queryClient = useQueryClient()

  return useMutation<
    ObSettings,
    ApiError,
    { data: ObSettingsWriteRequest; etag: string | null }
  >({
    mutationFn: async ({ data, etag }) => {
      const body = await http<{ data: ObSettings }>({
        url: '/onboarding/settings',
        method: 'PUT',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard, which is the failure the whole mechanism
        // exists to prevent — and on this screen the loser of the race loses
        // an escalation matrix, not a field.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: () => {
      // Refetched rather than written into the cache: the response carries a
      // new ETag in a header `http()` drops, and a cache holding fresh data
      // beside a stale tag would 412 the admin's very next save.
      void queryClient.invalidateQueries({ queryKey: OB_SETTINGS_KEY })
    },
  })
}
