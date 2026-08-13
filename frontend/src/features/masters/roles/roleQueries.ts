import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import { getListRolesQueryKey } from '@/api/generated/masters/masters'
import type { Permission } from '@/api/generated/model/permission'
import type { Role } from '@/api/generated/model/role'
import type { RoleDetail } from '@/api/generated/model/roleDetail'
import type { RolePatchRequest } from '@/api/generated/model/rolePatchRequest'
import type { RoleWriteRequest } from '@/api/generated/model/roleWriteRequest'

/**
 * B-015 · S-09's data layer.
 *
 * The same two things the generated client structurally cannot express, for the
 * same reasons `calendarQueries.ts` gives: orval omits header parameters, so
 * `Idempotency-Key` is hand-set, and `http()` drops the response object, so the
 * `ETag` that both writes need as `If-Match` has to come off a plain `fetch`.
 *
 * The catalogue and the role list use `http()` and the generated query keys, and
 * only the detail read is hand-written. Delete the hand-written parts the day
 * orval emits header params and a response-aware mutator.
 */

export const PERMISSIONS_KEY = ['/masters/permissions'] as const
export const ROLE_KEY = (roleId: number) => ['/masters/roles', roleId] as const

export interface RoleWithEtag {
  role: RoleDetail
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

/**
 * The capability catalogue — the matrix's row axis.
 *
 * Reference data that changes only by migration, so it is worth caching for the
 * session: `staleTime: Infinity` keeps a tab that visits three roles in a row
 * from fetching the same eighteen rows three times.
 */
export function usePermissionCatalogue() {
  return useQuery<Permission[], ApiError>({
    queryKey: PERMISSIONS_KEY,
    staleTime: Infinity,
    queryFn: async ({ signal }) => {
      const body = await http<{ data: Permission[] }>({
        url: '/masters/permissions',
        method: 'GET',
        signal,
      })
      return body.data
    },
  })
}

export function useRoles(params?: { isActive?: boolean }) {
  return useQuery<Role[], ApiError>({
    queryKey: getListRolesQueryKey(params),
    queryFn: async ({ signal }) => {
      const body = await http<{ data: Role[] }>({
        url: '/masters/roles',
        method: 'GET',
        params: params as Record<string, unknown>,
        signal,
      })
      return body.data
    },
  })
}

/**
 * Reads one role **and** its `ETag`.
 *
 * The tag is cached with the data deliberately, as the working week's is:
 * fetching it separately at submit time would read a value the user never saw,
 * which defeats the guard. The point is to detect that the role changed between
 * the read they edited and the write they sent.
 *
 * Because the tag covers `permissionCodes` too, a colleague's matrix save
 * invalidates a rename in progress — correctly, since both are edits to this
 * screen.
 */
export function useRole(roleId: number | null) {
  return useQuery<RoleWithEtag, ApiError>({
    queryKey: ROLE_KEY(roleId ?? -1),
    enabled: roleId != null,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/masters/roles/${roleId}`, {
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
      const body = (await response.json()) as { data: RoleDetail }
      return { role: body.data, etag: response.headers.get('ETag') }
    },
  })
}

function invalidate(queryClient: ReturnType<typeof useQueryClient>, roleId?: number) {
  // The list carries userCount and permissionCount, so any write staled it too.
  void queryClient.invalidateQueries({ queryKey: ['/masters/roles'] })
  void queryClient.invalidateQueries({ queryKey: getListRolesQueryKey() })
  void queryClient.invalidateQueries({ queryKey: getListRolesQueryKey({ isActive: true }) })
  if (roleId != null) {
    void queryClient.invalidateQueries({ queryKey: ROLE_KEY(roleId) })
  }
}

export function useCreateRole() {
  const queryClient = useQueryClient()

  return useMutation<RoleDetail, ApiError, RoleWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: RoleDetail }>({
        url: '/masters/roles',
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient),
  })
}

export function useUpdateRole() {
  const queryClient = useQueryClient()

  return useMutation<
    RoleDetail,
    ApiError,
    { roleId: number; data: RolePatchRequest; etag: string | null }
  >({
    mutationFn: async ({ roleId, data, etag }) => {
      const body = await http<{ data: RoleDetail }>({
        url: `/masters/roles/${roleId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client, which is the failure this
        // whole mechanism exists to prevent.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: (_role, { roleId }) => invalidate(queryClient, roleId),
  })
}

export function useDeleteRole() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, number>({
    mutationFn: (roleId) => http<void>({ url: `/masters/roles/${roleId}`, method: 'DELETE' }),
    onSuccess: () => invalidate(queryClient),
  })
}

/**
 * Replace-all: `permissionCodes` is the complete set the role should hold
 * afterwards, not a delta.
 */
export function useSaveRolePermissions() {
  const queryClient = useQueryClient()

  return useMutation<
    RoleDetail,
    ApiError,
    { roleId: number; permissionCodes: string[]; etag: string | null }
  >({
    mutationFn: async ({ roleId, permissionCodes, etag }) => {
      const body = await http<{ data: RoleDetail }>({
        url: `/masters/roles/${roleId}/permissions`,
        method: 'PUT',
        headers: { 'If-Match': etag ?? '*' },
        data: { permissionCodes },
      })
      return body.data
    },
    onSuccess: (_role, { roleId }) => invalidate(queryClient, roleId),
  })
}
