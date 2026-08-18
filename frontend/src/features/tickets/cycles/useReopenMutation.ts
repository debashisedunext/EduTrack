import { useMutation, useQueryClient } from '@tanstack/react-query'
import http from '@/api/http'
import { getListTicketsQueryKey } from '@/api/generated/tickets/tickets'
import type { ReopenRequest } from '@/api/generated/model/reopenRequest'
import type { TicketResponse } from '@/api/generated/model/ticketResponse'

export interface ReopenVariables {
  ticketId: string
  data: ReopenRequest
  /**
   * Minted once by the caller before the first attempt, same reason
   * `useQuickUpdateMutation`'s does — a retried request after a network
   * timeout must be recognised as the same reopen, not a second cycle.
   */
  idempotencyKey: string
}

/**
 * `POST /tickets/{ticketId}/reopen`, carrying an idempotency key.
 *
 * Hand-written for the same reason `useQuickUpdateMutation` is: orval drops
 * header parameters, so the generated `useReopenTicket` has nowhere to put
 * one. Delete this the day orval emits header params — call sites keep
 * working through the generated hook underneath.
 */
export function useReopenMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationKey: ['reopenTicket'],
    mutationFn: ({ ticketId, data, idempotencyKey }: ReopenVariables) =>
      http<TicketResponse>({
        url: `/tickets/${ticketId}/reopen`,
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        data,
      }),
    onSuccess: () => {
      // Status, cycle count and reopen count all moved, and the ticket list
      // (and My Tasks) render every one of them.
      void queryClient.invalidateQueries({ queryKey: getListTicketsQueryKey() })
    },
  })
}
