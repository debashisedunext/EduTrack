import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import { getListPrioritiesQueryKey } from '@/api/generated/masters/masters'
import type { Priority } from '@/api/generated/model/priority'
import type { PriorityPatchRequest } from '@/api/generated/model/priorityPatchRequest'
import type { PriorityWriteRequest } from '@/api/generated/model/priorityWriteRequest'

/**
 * B-021 · S-12's data layer.
 *
 * The same two things the generated client structurally cannot express, for the
 * reasons `roleQueries.ts`, `calendarQueries.ts` and `taskTypeQueries.ts` give:
 * orval omits header parameters, so `Idempotency-Key` is hand-set, and `http()`
 * drops the response object, so the `ETag` the `PATCH` needs as `If-Match` has
 * to come off a plain `fetch`. Delete the hand-written parts the day orval
 * emits header params and a response-aware mutator.
 */

export const PRIORITY_KEY = (priorityId: number) => ['/masters/priorities', priorityId] as const

export interface PriorityWithEtag {
  priority: Priority
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

/**
 * Every level, retired ones included — the grid's list, and **not** the one the
 * ticket screens use.
 *
 * The two are separated by the query key, which is what keeps them apart in the
 * cache: `getListPrioritiesQueryKey({ includeInactive: true })` is a different
 * key from the bare `getListPrioritiesQueryKey()` that `CreateTicketPage` and
 * `TicketListPage` hold. Sharing one key would put retired levels into the
 * create form's `LevelPicker` the moment an admin opened this screen — a cache
 * entry written by one screen and read, unfiltered, by another.
 */
export function usePriorities() {
  return useQuery<Priority[], ApiError>({
    queryKey: getListPrioritiesQueryKey({ includeInactive: true }),
    queryFn: async ({ signal }) => {
      const body = await http<{ data: Priority[] }>({
        url: '/masters/priorities',
        method: 'GET',
        params: { includeInactive: true },
        signal,
      })
      return body.data
    },
  })
}

/**
 * Reads one level **and** its `ETag`.
 *
 * The tag is cached with the data deliberately, as the working week's, the
 * role's and the task type's are: fetching it separately at submit time would
 * read a value the user never saw, which defeats the guard. The point is to
 * detect that the row changed between the read they edited and the write they
 * sent.
 */
export function usePriority(priorityId: number | null) {
  return useQuery<PriorityWithEtag, ApiError>({
    queryKey: PRIORITY_KEY(priorityId ?? -1),
    enabled: priorityId != null,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/masters/priorities/${priorityId}`, {
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
      const body = (await response.json()) as { data: Priority }
      return { priority: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * A save on this screen reaches further than any other master's.
 *
 * Both list keys are invalidated, because a save here changes what the create
 * form offers as well as what this grid shows. `/projects` goes too: every
 * project's SLA matrix is a task-type × **level** grid built from the active
 * levels, so retiring one, reordering one or changing one's default hours
 * changes a column, its position or every cell that falls through to rung 4.
 * Leaving it stale would show an SLA grid with a column for a level that has
 * just gone.
 *
 * Moving the escalation flag changes a *different* row than the one written, so
 * the whole list is invalidated rather than the single edited entry.
 */
function invalidate(queryClient: ReturnType<typeof useQueryClient>, priorityId?: number) {
  void queryClient.invalidateQueries({ queryKey: ['/masters/priorities'] })
  void queryClient.invalidateQueries({ queryKey: getListPrioritiesQueryKey() })
  void queryClient.invalidateQueries({
    queryKey: getListPrioritiesQueryKey({ includeInactive: true }),
  })
  void queryClient.invalidateQueries({ queryKey: ['/projects'] })
  if (priorityId != null) {
    void queryClient.invalidateQueries({ queryKey: PRIORITY_KEY(priorityId) })
  }
}

export function useCreatePriority() {
  const queryClient = useQueryClient()

  return useMutation<Priority, ApiError, PriorityWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: Priority }>({
        url: '/masters/priorities',
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
 * The one write that edits, and the one that retires — there is no delete.
 *
 * `isActive: false` is how a level goes away. Nothing has a foreign key to
 * `priorities`: `tickets.level`, `task_types.default_level` and
 * `sla_policies.level` all hold the code as a string, so a delete would
 * *succeed* and leave every ticket ever raised at that level rendering a code
 * nothing resolves. That is a weaker safety net than the task type master's,
 * where three foreign keys would at least make a delete fail loudly.
 */
export function useUpdatePriority() {
  const queryClient = useQueryClient()

  return useMutation<
    Priority,
    ApiError,
    { priorityId: number; data: PriorityPatchRequest; etag: string | null }
  >({
    mutationFn: async ({ priorityId, data, etag }) => {
      const body = await http<{ data: Priority }>({
        url: `/masters/priorities/${priorityId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client, which is the failure the
        // whole mechanism exists to prevent.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: (_priority, { priorityId }) => invalidate(queryClient, priorityId),
  })
}
