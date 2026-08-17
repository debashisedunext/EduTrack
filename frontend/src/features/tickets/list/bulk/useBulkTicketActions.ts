import { useMutation, useQueryClient } from '@tanstack/react-query'

import http from '@/api/http'
import { getListTicketsQueryKey } from '@/api/generated/tickets/tickets'
import type { BulkResultResponse } from '@/api/generated/model/bulkResultResponse'
import type { BulkReassignTicketsBody } from '@/api/generated/model/bulkReassignTicketsBody'
import type { BulkChangeTicketLevelBody } from '@/api/generated/model/bulkChangeTicketLevelBody'
import type { BulkCloseTicketsBody } from '@/api/generated/model/bulkCloseTicketsBody'

/**
 * C-017 · the three bulk endpoints behind S-17's selection bar.
 *
 * Hand-written rather than through the generated `useBulkReassignTickets` and
 * friends, for the reason `useQuickUpdateMutation` and `createTicketMutation`
 * already carry: **orval drops header parameters**, and all three of these take
 * an `Idempotency-Key`. Delete these the day orval emits header params — the
 * call sites keep working through the generated hook underneath.
 *
 * The key matters more here than almost anywhere else in the product. A bulk
 * close that times out on the wire has still closed fifty tickets; retrying it
 * without a key would try to close them again, and the server would refuse each
 * one as `Already closed` — turning a successful operation into a screen full
 * of failures. So the key is minted **once per attempt by the caller**, before
 * the first request, not inside `mutationFn` where a retry would change it and
 * defend against nothing.
 */

export interface BulkVariables<TBody> {
  data: TBody
  /** Minted once by the caller before the first attempt. See above. */
  idempotencyKey: string
}

/**
 * Every bulk action invalidates the whole ticket list.
 *
 * Not a surgical per-row update: a level change moves the planned close date,
 * a close changes the status, and either can push a row out of the active
 * filter set entirely — "Due Today" after a reschedule, or any view carrying
 * `excludeClosed`. Patching the cache row by row would leave rows visible that
 * no longer match the query that produced them.
 */
function useInvalidateTicketList() {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: getListTicketsQueryKey() })
  }
}

export function useBulkReassign() {
  const invalidate = useInvalidateTicketList()
  return useMutation({
    mutationKey: ['bulkReassignTickets'],
    mutationFn: ({ data, idempotencyKey }: BulkVariables<BulkReassignTicketsBody>) =>
      http<BulkResultResponse>({
        url: '/tickets/bulk-reassign',
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        data,
      }),
    onSuccess: invalidate,
  })
}

export function useBulkChangeLevel() {
  const invalidate = useInvalidateTicketList()
  return useMutation({
    mutationKey: ['bulkChangeTicketLevel'],
    mutationFn: ({ data, idempotencyKey }: BulkVariables<BulkChangeTicketLevelBody>) =>
      http<BulkResultResponse>({
        url: '/tickets/bulk-level',
        method: 'PATCH',
        headers: { 'Idempotency-Key': idempotencyKey },
        data,
      }),
    onSuccess: invalidate,
  })
}

export function useBulkClose() {
  const invalidate = useInvalidateTicketList()
  return useMutation({
    mutationKey: ['bulkCloseTickets'],
    mutationFn: ({ data, idempotencyKey }: BulkVariables<BulkCloseTicketsBody>) =>
      http<BulkResultResponse>({
        url: '/tickets/bulk-close',
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        data,
      }),
    onSuccess: invalidate,
  })
}
