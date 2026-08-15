import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import { getListTaskTypesQueryKey } from '@/api/generated/masters/masters'
import type { TaskType } from '@/api/generated/model/taskType'
import type { TaskTypePatchRequest } from '@/api/generated/model/taskTypePatchRequest'
import type { TaskTypeWriteRequest } from '@/api/generated/model/taskTypeWriteRequest'

/**
 * B-020 · S-11's data layer.
 *
 * The same two things the generated client structurally cannot express, for the
 * reasons `roleQueries.ts` and `calendarQueries.ts` give: orval omits header
 * parameters, so `Idempotency-Key` is hand-set, and `http()` drops the response
 * object, so the `ETag` the `PATCH` needs as `If-Match` has to come off a plain
 * `fetch`. Delete the hand-written parts the day orval emits header params and
 * a response-aware mutator.
 */

export const TASK_TYPE_KEY = (taskTypeId: number) => ['/masters/task-types', taskTypeId] as const

export interface TaskTypeWithEtag {
  taskType: TaskType
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

/**
 * Every row, retired ones included, in `seq` order.
 *
 * The generated query key is shared with `useListTaskTypes`, which the ticket
 * list, the ticket detail page and the create form all call — so a save here
 * refreshes the picker on every one of them without any of them knowing this
 * screen exists.
 */
export function useTaskTypes() {
  return useQuery<TaskType[], ApiError>({
    queryKey: getListTaskTypesQueryKey(),
    queryFn: async ({ signal }) => {
      const body = await http<{ data: TaskType[] }>({
        url: '/masters/task-types',
        method: 'GET',
        signal,
      })
      return body.data
    },
  })
}

/**
 * Reads one task type **and** its `ETag`.
 *
 * The tag is cached with the data deliberately, as the working week's and the
 * role's are: fetching it separately at submit time would read a value the user
 * never saw, which defeats the guard. The point is to detect that the row
 * changed between the read they edited and the write they sent.
 */
export function useTaskType(taskTypeId: number | null) {
  return useQuery<TaskTypeWithEtag, ApiError>({
    queryKey: TASK_TYPE_KEY(taskTypeId ?? -1),
    enabled: taskTypeId != null,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/masters/task-types/${taskTypeId}`, {
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
      const body = (await response.json()) as { data: TaskType }
      return { taskType: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * A save reaches further than this screen.
 *
 * Retiring a type removes it from the create form's picker and from every
 * project's SLA matrix, so both are invalidated — the SLA grid is built from
 * the active types and would otherwise keep rendering a row for a type that has
 * just gone.
 */
function invalidate(queryClient: ReturnType<typeof useQueryClient>, taskTypeId?: number) {
  void queryClient.invalidateQueries({ queryKey: ['/masters/task-types'] })
  void queryClient.invalidateQueries({ queryKey: getListTaskTypesQueryKey() })
  void queryClient.invalidateQueries({ queryKey: ['/projects'] })
  if (taskTypeId != null) {
    void queryClient.invalidateQueries({ queryKey: TASK_TYPE_KEY(taskTypeId) })
  }
}

export function useCreateTaskType() {
  const queryClient = useQueryClient()

  return useMutation<TaskType, ApiError, TaskTypeWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: TaskType }>({
        url: '/masters/task-types',
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
 * `isActive: false` is how a task type goes away, because three foreign keys
 * point at the table without cascades and a delete would either fail on an
 * index name or silently rewrite what a historical ticket was raised against.
 */
export function useUpdateTaskType() {
  const queryClient = useQueryClient()

  return useMutation<
    TaskType,
    ApiError,
    { taskTypeId: number; data: TaskTypePatchRequest; etag: string | null }
  >({
    mutationFn: async ({ taskTypeId, data, etag }) => {
      const body = await http<{ data: TaskType }>({
        url: `/masters/task-types/${taskTypeId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client, which is the failure the
        // whole mechanism exists to prevent.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: (_type, { taskTypeId }) => invalidate(queryClient, taskTypeId),
  })
}
