import { useMutation, useQueryClient } from '@tanstack/react-query'
import http from '@/api/http'
import { getListTicketsQueryKey } from '@/api/generated/tickets/tickets'
import type { QuickUpdateRequest } from '@/api/generated/model/quickUpdateRequest'
import type { TicketResponse } from '@/api/generated/model/ticketResponse'

export interface QuickUpdateVariables {
  ticketId: string
  data: QuickUpdateRequest
  /**
   * Minted once by the caller before the first attempt — see
   * `newIdempotencyKey`'s own doc comment. Generating it inside `mutationFn`
   * would change on every retry and defend against nothing, which is exactly
   * the case this panel cares about: a retried request after a network
   * timeout must not double-log the effort hours it carries.
   */
  idempotencyKey: string
}

/**
 * `POST /tickets/{ticketId}/quick-update`, carrying an idempotency key.
 *
 * Same reason `createTicketMutation.ts` hand-writes its call instead of using
 * the generated `useQuickUpdateTicket`: orval drops header parameters. Delete
 * this the day orval emits header params — call sites keep working through
 * the generated hook underneath.
 */
export function useQuickUpdateMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationKey: ['quickUpdateTicket'],
    mutationFn: ({ ticketId, data, idempotencyKey }: QuickUpdateVariables) =>
      http<TicketResponse>({
        url: `/tickets/${ticketId}/quick-update`,
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        data,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: getListTicketsQueryKey() })
    },
  })
}
