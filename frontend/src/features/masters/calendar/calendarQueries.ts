import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import { getGetWorkingCalendarQueryKey } from '@/api/generated/masters/masters'
import type { WorkingWeek } from '@/api/generated/model/workingWeek'
import type { WorkingWeekUpdateRequest } from '@/api/generated/model/workingWeekUpdateRequest'
import type { Holiday } from '@/api/generated/model/holiday'
import type { HolidayWriteRequest } from '@/api/generated/model/holidayWriteRequest'
import type { ResourceLeave } from '@/api/generated/model/resourceLeave'
import type { ResourceLeaveWriteRequest } from '@/api/generated/model/resourceLeaveWriteRequest'

/**
 * B-023 · the calendar's data layer.
 *
 * Most of this could be the generated hooks. Two things cannot, and they are
 * the same two the generated client structurally cannot express:
 *
 * 1. **The working week's `ETag`.** `http()` returns a parsed body and drops the
 *    response, which is right for every other call — but the optimistic
 *    concurrency guard on this resource is the header. So the read is a plain
 *    `fetch` and hands the tag back alongside the data.
 * 2. **`Idempotency-Key` on creates.** Orval omits header parameters, the same
 *    gap C-010 worked around in `createTicketMutation.ts`.
 *
 * Delete the hand-written parts the day orval emits header params and a
 * response-aware mutator; the call sites keep working.
 */

export const WORKING_WEEK_KEY = ['/masters/working-calendar'] as const
export const LEAVES_KEY = ['/masters/leaves'] as const

export interface WorkingWeekWithEtag {
  week: WorkingWeek
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

/**
 * Reads the working week **and** its `ETag`.
 *
 * The tag is cached with the data deliberately. Fetching it separately at
 * submit time would read a value the user never saw, which would defeat the
 * guard: the point is to detect that the week changed between the read they
 * edited and the write they sent.
 */
export function useWorkingWeek() {
  return useQuery<WorkingWeekWithEtag, ApiError>({
    queryKey: WORKING_WEEK_KEY,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/masters/working-calendar`, {
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
      const body = (await response.json()) as { data: WorkingWeek }
      return { week: body.data, etag: response.headers.get('ETag') }
    },
  })
}

export function useUpdateWorkingWeek() {
  const queryClient = useQueryClient()

  return useMutation<WorkingWeek, ApiError, { data: WorkingWeekUpdateRequest; etag: string | null }>({
    mutationFn: async ({ data, etag }) => {
      const body = await http<{ data: WorkingWeek }>({
        url: '/masters/working-calendar',
        method: 'PUT',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client, which is the failure this
        // whole mechanism exists to prevent.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: WORKING_WEEK_KEY })
      // The holiday read model carries the same three fields, so it is stale too.
      void queryClient.invalidateQueries({ queryKey: getGetWorkingCalendarQueryKey() })
    },
  })
}

// ── holidays ────────────────────────────────────────────────────────────────

function invalidateCalendar(queryClient: ReturnType<typeof useQueryClient>) {
  void queryClient.invalidateQueries({ queryKey: getGetWorkingCalendarQueryKey() })
}

export function useCreateHoliday() {
  const queryClient = useQueryClient()

  return useMutation<Holiday, ApiError, HolidayWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: Holiday }>({
        url: '/masters/holidays',
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidateCalendar(queryClient),
  })
}

export function useUpdateHoliday() {
  const queryClient = useQueryClient()

  return useMutation<Holiday, ApiError, { id: number; data: HolidayWriteRequest }>({
    mutationFn: async ({ id, data }) => {
      const body = await http<{ data: Holiday }>({
        url: `/masters/holidays/${id}`,
        method: 'PATCH',
        data,
      })
      return body.data
    },
    onSuccess: () => invalidateCalendar(queryClient),
  })
}

export function useDeleteHoliday() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, number>({
    mutationFn: (id) => http<void>({ url: `/masters/holidays/${id}`, method: 'DELETE' }),
    onSuccess: () => invalidateCalendar(queryClient),
  })
}

// ── resource leave ──────────────────────────────────────────────────────────

export function useResourceLeaves(params?: { userId?: number; from?: string; to?: string }) {
  return useQuery<{ data: ResourceLeave[] }, ApiError>({
    queryKey: [...LEAVES_KEY, params ?? {}],
    queryFn: ({ signal }) =>
      http<{ data: ResourceLeave[] }>({
        url: '/masters/leaves',
        method: 'GET',
        params: params as Record<string, unknown>,
        signal,
      }),
  })
}

export function useCreateLeave() {
  const queryClient = useQueryClient()

  return useMutation<ResourceLeave, ApiError, ResourceLeaveWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: ResourceLeave }>({
        url: '/masters/leaves',
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: LEAVES_KEY })
    },
  })
}

export function useDeleteLeave() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, number>({
    mutationFn: (id) => http<void>({ url: `/masters/leaves/${id}`, method: 'DELETE' }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: LEAVES_KEY })
    },
  })
}
