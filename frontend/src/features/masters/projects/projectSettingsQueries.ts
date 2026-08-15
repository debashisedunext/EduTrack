import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { ApiError, BASE, getAccessToken, type Problem } from '@/api/http'
import type { ProjectSettings } from '@/api/generated/model/projectSettings'
import type { ProjectSettingsWrite } from '@/api/generated/model/projectSettingsWrite'

/**
 * B-019 · the Settings tab's data layer.
 *
 * Hand-written for the reason `projectQueries.ts` and `slaMatrixQueries.ts`
 * both give: `http()` drops the response object, so the `ETag` the `PUT` needs
 * as `If-Match` has to come off a plain `fetch`. Orval's `getProjectSettings`
 * cannot supply it either — it returns the parsed body and nothing else. Delete
 * this the day orval emits a response-aware mutator.
 *
 * The tag is cached **with** the document rather than fetched at submit time.
 * Refetching it would read a value the user never saw, which defeats the guard
 * entirely — the point of a precondition is that it fails when the thing you
 * were looking at has moved.
 */

export const PROJECT_SETTINGS_KEY = (projectId: number) =>
  ['/projects', projectId, 'settings'] as const

export interface ProjectSettingsWithEtag {
  settings: ProjectSettings
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

export function useProjectSettings(projectId: number | null) {
  return useQuery<ProjectSettingsWithEtag, ApiError>({
    queryKey: PROJECT_SETTINGS_KEY(projectId ?? -1),
    enabled: projectId != null,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/projects/${projectId}/settings`, {
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
      const body = (await response.json()) as { data: ProjectSettings }
      return { settings: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * Replace the settings.
 *
 * `body` is passed through untouched. **Nothing here may pad or "helpfully"
 * complete `allowedTaskTypeIds`** — an empty array is how the restriction is
 * removed, and code that filled it in with every visible task type would turn
 * "this project restricts nothing" into "this project allows exactly the eleven
 * types that existed today", silently barring the twelfth an Admin adds next
 * month. `toWriteRequest` in `projectSettings.ts` is the one place that decides
 * what goes in it.
 *
 * The response is the settings as they now read, so it is written straight into
 * the cache rather than only invalidated: a cleared allow-list comes back with
 * every task type allowed again, and re-rendering from the request body would
 * show the user an empty list where the server has a perfectly good unrestricted
 * one.
 */
export function useReplaceProjectSettings(projectId: number) {
  const queryClient = useQueryClient()

  return useMutation<
    ProjectSettingsWithEtag,
    ApiError,
    { body: ProjectSettingsWrite; etag: string | null }
  >({
    mutationFn: async ({ body, etag }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/projects/${projectId}/settings`, {
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
        body: JSON.stringify(body),
      })
      if (!response.ok) {
        throw new ApiError(response.status, await readProblem(response), response)
      }
      const saved = (await response.json()) as { data: ProjectSettings }
      return { settings: saved.data, etag: response.headers.get('ETag') }
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(PROJECT_SETTINGS_KEY(projectId), saved)
      // `autoAssignRule` is a project column, and the General tab renders the
      // project. Leaving it cached would have two tabs of one screen showing
      // different values for one field until a reload.
      void queryClient.invalidateQueries({ queryKey: ['/projects', projectId] })
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
