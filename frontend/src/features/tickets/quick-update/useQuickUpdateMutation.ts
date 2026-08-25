import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import http from '@/api/http'
import { getGetTicketDetailQueryKey, getListTicketsQueryKey } from '@/api/generated/tickets/tickets'
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
/**
 * Everything a quick update makes stale, in one place.
 *
 * BUG-002 · this used to invalidate the ticket list alone, so the detail page
 * — which reads the aggregated `/tickets/{id}/full` under its own key — kept
 * serving the pre-update ticket until a hard reload. The panel is reachable
 * from the detail page, My Tasks and the list, and every one of them was
 * showing a stale ticket after a successful write.
 *
 * `getGetTicketDetailQueryKey(ticketId)` is passed without params on purpose:
 * that makes it a prefix of the keys the page actually uses, so the cycle the
 * user happens to have selected (`?cycle=`) is invalidated along with the
 * default one rather than only whichever variant we guessed.
 *
 * Exported because the level control inside the panel writes through its own
 * mutation and has the same two things to invalidate.
 */
export function invalidateAfterTicketWrite(queryClient: QueryClient, ticketId: string) {
  void queryClient.invalidateQueries({ queryKey: getListTicketsQueryKey() })
  void queryClient.invalidateQueries({ queryKey: getGetTicketDetailQueryKey(ticketId) })
}

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
    onSuccess: (_result, { ticketId }) => {
      invalidateAfterTicketWrite(queryClient, ticketId)
    },
  })
}
