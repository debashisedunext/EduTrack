import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { ApiError, BASE, getAccessToken, type Problem } from '@/api/http'
import type { SlaPolicyCell } from '@/api/generated/model/slaPolicyCell'
import type { SlaPolicyWrite } from '@/api/generated/model/slaPolicyWrite'

/**
 * B-018 · the SLA tab's data layer.
 *
 * Hand-written for the reason `projectQueries.ts` gives: `http()` drops the
 * response object, so the `ETag` the `PUT` needs as `If-Match` has to come off a
 * plain `fetch`. Orval's `getSlaPolicies` cannot supply it either — it returns
 * the parsed body and nothing else. Delete this the day orval emits a
 * response-aware mutator.
 *
 * The precondition matters more here than on any other write in the feature.
 * Every other one patches a field of a row; this one **replaces the whole
 * matrix**, so a stale tab's save does not merge with another administrator's,
 * it erases it. That is the case CONVENTIONS.md §5 calls the worst for a lost
 * update, and the reason the tag is cached with the grid rather than fetched at
 * submit time: refetching it would read a value the user never saw, which
 * defeats the guard entirely.
 */

export const SLA_MATRIX_KEY = (projectId: number) =>
  ['/projects', projectId, 'sla-policies'] as const

export interface SlaMatrixWithEtag {
  cells: SlaPolicyCell[]
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

export function useSlaMatrix(projectId: number | null) {
  return useQuery<SlaMatrixWithEtag, ApiError>({
    queryKey: SLA_MATRIX_KEY(projectId ?? -1),
    enabled: projectId != null,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/projects/${projectId}/sla-policies`, {
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
      const body = (await response.json()) as { data: SlaPolicyCell[] }
      return { cells: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * Replace the matrix.
 *
 * `overrides` is passed through untouched. **Nothing here may filter, pad or
 * "helpfully" complete it** — the body is this project's overrides and a cell
 * left out goes back to inheriting, so anything that added the missing cells
 * would materialise every inherited figure as a project row and silently
 * detach the project from its defaults. `buildOverrides` in `slaMatrix.ts` is
 * the one place that decides what belongs in it.
 *
 * The response is the newly resolved grid, so it is written straight into the
 * cache rather than only invalidated: a cleared override resolves to whatever
 * it now inherits, and re-rendering from the request body would show the user
 * an empty cell where the server has a perfectly good inherited figure.
 */
export function useReplaceSlaMatrix(projectId: number) {
  const queryClient = useQueryClient()

  return useMutation<SlaMatrixWithEtag, ApiError, { overrides: SlaPolicyWrite[]; etag: string | null }>({
    mutationFn: async ({ overrides, etag }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/projects/${projectId}/sla-policies`, {
        method: 'PUT',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          // `*` only when the server never gave us a tag. Sending it routinely
          // would disable the guard for every client, which is the failure the
          // whole mechanism exists to prevent.
          'If-Match': etag ?? '*',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify(overrides),
      })
      if (!response.ok) {
        throw new ApiError(response.status, await readProblem(response), response)
      }
      const body = (await response.json()) as { data: SlaPolicyCell[] }
      return { cells: body.data, etag: response.headers.get('ETag') }
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(SLA_MATRIX_KEY(projectId), saved)
      // The planned-close-date preview on the create form resolves through the
      // same rows. Leaving it cached would have the ticket form quoting the
      // policy that was in force before this save — the exact disagreement
      // C-012 exists to prevent.
      void queryClient.invalidateQueries({ queryKey: ['/tickets', 'planned-close-date'] })
    },
  })
}

/**
 * `http()` has one of these and does not export it, and the 400 this write can
 * answer carries the `errors` map the banner shows — so a bare
 * `response.statusText` would drop the one part of the response the user needs.
 */
async function readProblem(response: Response): Promise<Problem> {
  const fallback: Problem = {
    type: 'about:blank',
    title: response.statusText,
    status: response.status,
  }
  try {
    const body = (await response.json()) as Problem
    return typeof body === 'object' && body !== null ? body : fallback
  } catch {
    // Never let error handling throw. A gateway timeout or a proxy returning
    // HTML would otherwise fail inside the parse and surface as "Unexpected
    // token <", which sends the developer to the wrong layer entirely.
    return fallback
  }
}
