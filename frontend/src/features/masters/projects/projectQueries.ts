import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import { getListProjectsQueryKey } from '@/api/generated/projects/projects'
import type { ListProjectsParams } from '@/api/generated/model/listProjectsParams'
import type { Project } from '@/api/generated/model/project'
import type { ProjectDetail } from '@/api/generated/model/projectDetail'
import type { ProjectPatchRequest } from '@/api/generated/model/projectPatchRequest'
import type { ProjectWriteRequest } from '@/api/generated/model/projectWriteRequest'

/**
 * B-016 · S-10's data layer.
 *
 * The same two things the generated client structurally cannot express, for the
 * same reasons `roleQueries.ts` and `calendarQueries.ts` give: orval omits header
 * parameters, so `Idempotency-Key` is hand-set, and `http()` drops the response
 * object, so the `ETag` the `PATCH` needs as `If-Match` has to come off a plain
 * `fetch`.
 *
 * The list uses `http()` and the generated query key; only the detail read is
 * hand-written. Delete the hand-written parts the day orval emits header params
 * and a response-aware mutator.
 */

export const PROJECT_KEY = (projectId: number) => ['/projects', projectId] as const

export interface ProjectWithEtag {
  project: ProjectDetail
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

export function useProjects(params?: ListProjectsParams) {
  return useQuery<{ data: Project[]; meta?: { nextCursor?: string | null; hasMore?: boolean } }, ApiError>({
    queryKey: getListProjectsQueryKey(params),
    queryFn: ({ signal }) =>
      http({
        url: '/projects',
        method: 'GET',
        params: params as Record<string, unknown>,
        signal,
      }),
  })
}

/**
 * Reads one project **and** its `ETag`.
 *
 * The tag is cached with the data deliberately, as the role master's is:
 * fetching it separately at submit time would read a value the user never saw,
 * which defeats the guard. The point is to detect that the project changed
 * between the read they edited and the write they sent.
 *
 * Because `ticketsIssued` is part of the tag, a ticket allocated while the form
 * is open invalidates it — correctly, since that is the event that fixes
 * `projectCode`.
 */
export function useProject(projectId: number | null) {
  return useQuery<ProjectWithEtag, ApiError>({
    queryKey: PROJECT_KEY(projectId ?? -1),
    enabled: projectId != null,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/projects/${projectId}`, {
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
      const body = (await response.json()) as { data: ProjectDetail }
      return { project: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * Every list variant, not only the one this screen used.
 *
 * A project created here has to appear in the project switcher, the ticket
 * list's filter, the create-ticket form and both resource screens — all of which
 * cache `?isActive=true&limit=200` under their own key. Invalidating the prefix
 * catches every one of them, which is why the key is a plain array and the
 * prefix match is worth having.
 */
function invalidate(queryClient: ReturnType<typeof useQueryClient>, projectId?: number) {
  void queryClient.invalidateQueries({ queryKey: ['/projects'] })
  void queryClient.invalidateQueries({ queryKey: getListProjectsQueryKey() })
  if (projectId != null) {
    void queryClient.invalidateQueries({ queryKey: PROJECT_KEY(projectId) })
  }
}

export function useCreateProject() {
  const queryClient = useQueryClient()

  return useMutation<ProjectDetail, ApiError, ProjectWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: ProjectDetail }>({
        url: '/projects',
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient),
  })
}

export function useUpdateProject() {
  const queryClient = useQueryClient()

  return useMutation<
    ProjectDetail,
    ApiError,
    { projectId: number; data: ProjectPatchRequest; etag: string | null }
  >({
    mutationFn: async ({ projectId, data, etag }) => {
      const body = await http<{ data: ProjectDetail }>({
        url: `/projects/${projectId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client, which is the failure the
        // whole mechanism exists to prevent.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: (_project, { projectId }) => invalidate(queryClient, projectId),
  })
}
