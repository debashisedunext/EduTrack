import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import type { Meta } from '@/api/generated/model/meta'
import type { ObProject } from '@/api/generated/model/obProject'
import type { ObProjectCreateRequest } from '@/api/generated/model/obProjectCreateRequest'
import type { ObProjectDetail } from '@/api/generated/model/obProjectDetail'
import type { ObProjectUpdateRequest } from '@/api/generated/model/obProjectUpdateRequest'
import { invalidateObDashboard } from '@/features/onboarding/dashboard/obDashboardFreshness'

/**
 * The data layer for the Projects grid, the New Project form and the project
 * header.
 *
 * Hand-written for the two things orval cannot express, on
 * `productQueries.ts`'s own account: header parameters are omitted from the
 * generated client, so `Idempotency-Key` and `If-Match` are set here, and
 * `http()` drops the response object, so the `ETag` the `PATCH` requires comes
 * off a plain `fetch`.
 */

const PROJECTS_PREFIX = ['/onboarding/projects'] as const

export const PROJECT_KEY = (obProjectId: number) => ['/onboarding/projects', obProjectId] as const

export interface ProjectFilters {
  q?: string
  clientId?: number | null
  productId?: number | null
  status?: string | null
  implementorId?: number | null
  salesPersonId?: number | null
  cursor?: string | null
  limit?: number
}

export interface ProjectPage {
  data: ObProject[]
  meta?: Meta
}

export interface ProjectWithEtag {
  project: ObProjectDetail
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

/**
 * The query string, built from the filters that are actually set.
 *
 * Exported because the grid's tests assert on it directly: an empty filter must
 * produce no parameter at all rather than `clientId=null`, which the server
 * would read as a filter narrowing to nothing.
 */
export function projectQueryString(filters: ProjectFilters): string {
  const search = new URLSearchParams()
  if (filters.q?.trim()) search.set('q', filters.q.trim())
  if (filters.clientId != null) search.set('clientId', String(filters.clientId))
  if (filters.productId != null) search.set('productId', String(filters.productId))
  if (filters.status) search.set('status', filters.status)
  if (filters.implementorId != null) search.set('implementorId', String(filters.implementorId))
  if (filters.salesPersonId != null) search.set('salesPersonId', String(filters.salesPersonId))
  if (filters.cursor) search.set('cursor', filters.cursor)
  if (filters.limit) search.set('limit', String(filters.limit))
  return search.toString()
}

export function useObProjects(filters: ProjectFilters) {
  const query = projectQueryString(filters)
  return useQuery<ProjectPage, ApiError>({
    queryKey: [...PROJECTS_PREFIX, 'list', query],
    queryFn: ({ signal }) =>
      http<ProjectPage>({
        url: `/onboarding/projects${query ? `?${query}` : ''}`,
        method: 'GET',
        signal,
      }),
  })
}

/**
 * One project and its `ETag`.
 *
 * The tag covers the stage roll-up, so a step completed by an owner while
 * somebody has the header open costs a reload. That is the server's choice and
 * this layer only carries it; caching the tag with the data rather than
 * fetching it at submit time is what makes the guard mean anything.
 */
export function useObProject(obProjectId: number | null) {
  return useQuery<ProjectWithEtag, ApiError>({
    queryKey: PROJECT_KEY(obProjectId ?? -1),
    enabled: obProjectId != null,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/onboarding/projects/${obProjectId}`, {
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
      const body = (await response.json()) as { data: ObProjectDetail }
      return { project: body.data, etag: response.headers.get('ETag') }
    },
  })
}

function invalidate(queryClient: ReturnType<typeof useQueryClient>) {
  void queryClient.invalidateQueries({ queryKey: PROJECTS_PREFIX })
  /*
    Creating a project instantiates journeys against its client, which moves
    `journeyCount` on the Clients master and the whole client detail document.
    Invalidating both is cheaper than leaving either showing a figure that was
    true a second ago.
  */
  void queryClient.invalidateQueries({ queryKey: ['/onboarding/clients'] })
  /*
    And OB-02, which is the screen somebody actually goes looking at after
    creating a project. Its RAG columns, stuck panel and delayed-projects grid
    are live reads that move immediately; the seven counters move on B-120's
    next pass. Leaving this out is what made a new project invisible on the
    dashboard for as long as the default `staleTime` held the cache.
  */
  invalidateObDashboard(queryClient)
}

export function useCreateObProject() {
  const queryClient = useQueryClient()

  return useMutation<ObProjectDetail, ApiError, ObProjectCreateRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: ObProjectDetail }>({
        url: '/onboarding/projects',
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient),
  })
}

export function useUpdateObProject() {
  const queryClient = useQueryClient()

  return useMutation<
    ObProjectDetail,
    ApiError,
    { obProjectId: number; data: ObProjectUpdateRequest; etag: string | null }
  >({
    mutationFn: async ({ obProjectId, data, etag }) => {
      const body = await http<{ data: ObProjectDetail }>({
        url: `/onboarding/projects/${obProjectId}`,
        method: 'PATCH',
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient),
  })
}

/**
 * Delete a project that never ran.
 *
 * <p>Answers `409 ob-project-in-use` with a `blockers` array when something
 * records that it did — step history, logged time, sign-offs, communications,
 * escalations, documents or sent notifications. The screen reads that array
 * rather than the sentence, because naming them is what makes "set it to
 * Dropped instead" an informed choice rather than a shrug.
 */
export function useDeleteObProject() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, number>({
    mutationFn: async (obProjectId) => {
      await http<void>({ url: `/onboarding/projects/${obProjectId}`, method: 'DELETE' })
    },
    onSuccess: () => invalidate(queryClient),
  })
}

/** The `blockers` an `ob-project-in-use` problem carries, or an empty list. */
export function projectBlockersFrom(error: ApiError): string[] {
  const problem = error.problem as { blockers?: unknown }
  return Array.isArray(problem.blockers) ? problem.blockers.map(String) : []
}

/** The `existingProjectId` an `ob-project-duplicate` problem carries, or null. */
export function existingProjectIdFrom(error: ApiError): number | null {
  const problem = error.problem as { existingProjectId?: unknown }
  return typeof problem.existingProjectId === 'number' ? problem.existingProjectId : null
}
