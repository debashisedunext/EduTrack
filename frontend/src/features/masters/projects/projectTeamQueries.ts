import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import http, { ApiError, newIdempotencyKey } from '@/api/http'
import type { ProjectMember } from '@/api/generated/model/projectMember'
import type { ProjectMemberPatch } from '@/api/generated/model/projectMemberPatch'
import type { ProjectMemberWrite } from '@/api/generated/model/projectMemberWrite'

/**
 * B-017 · the Team tab's data layer.
 *
 * `http()` throughout, with the generated types — nothing here needs the two
 * escapes `projectQueries.ts` documents. There is no `ETag` on the roster and
 * no `If-Match` on the patch (a registered CONVENTIONS.md §5 exemption: the tag
 * would have to come from a collection read that has none), so the only
 * hand-written header is `Idempotency-Key` on the create, which orval omits.
 */

export const TEAM_KEY = (projectId: number) => ['/projects', projectId, 'members'] as const

export function useProjectTeam(projectId: number | null) {
  return useQuery<ProjectMember[], ApiError>({
    queryKey: TEAM_KEY(projectId ?? -1),
    enabled: projectId != null,
    queryFn: async ({ signal }) => {
      const body = await http<{ data: ProjectMember[] }>({
        url: `/projects/${projectId}/members`,
        method: 'GET',
        signal,
      })
      return body.data
    },
  })
}

/**
 * Invalidates the roster **and the resource screens**.
 *
 * A membership written here is the same `project_members` row S-08's Projects
 * section reads, and the S-07 grid prints a project column from it. Invalidating
 * only `TEAM_KEY` would leave both showing a team the person is no longer on
 * until something else happened to refetch — the two screens edit one table from
 * two sides, and the cache has to know that.
 */
function invalidate(queryClient: ReturnType<typeof useQueryClient>, projectId: number) {
  void queryClient.invalidateQueries({ queryKey: TEAM_KEY(projectId) })
  void queryClient.invalidateQueries({ queryKey: ['/users'] })
}

export function useAddTeamMember(projectId: number) {
  const queryClient = useQueryClient()

  return useMutation<ProjectMember, ApiError, ProjectMemberWrite>({
    mutationFn: async (data) => {
      const body = await http<{ data: ProjectMember }>({
        url: `/projects/${projectId}/members`,
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient, projectId),
  })
}

/**
 * The inline edit.
 *
 * **`patch` is passed through untouched, and that is deliberate.** The operation
 * distinguishes an omitted key from an explicit null — omitted keeps the stored
 * value, null clears it — so anything here that "helpfully" filled in the
 * missing field would make clearing impossible, and anything that stripped nulls
 * would make it silently a no-op. `buildMemberPatch` in `projectTeam.ts` is the
 * one place that decides which of the two a UI change means.
 */
export function useUpdateTeamMember(projectId: number) {
  const queryClient = useQueryClient()

  return useMutation<ProjectMember, ApiError, { userId: number; patch: ProjectMemberPatch }>({
    mutationFn: async ({ userId, patch }) => {
      const body = await http<{ data: ProjectMember }>({
        url: `/projects/${projectId}/members/${userId}`,
        method: 'PATCH',
        data: patch,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient, projectId),
  })
}

export function useRemoveTeamMember(projectId: number) {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, number>({
    mutationFn: (userId) =>
      http<void>({ url: `/projects/${projectId}/members/${userId}`, method: 'DELETE' }),
    onSuccess: () => invalidate(queryClient, projectId),
  })
}
