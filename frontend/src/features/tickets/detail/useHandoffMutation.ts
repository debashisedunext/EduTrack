import { useMutation, useQueryClient } from '@tanstack/react-query'
import http from '@/api/http'
import { getListTicketsQueryKey } from '@/api/generated/tickets/tickets'
import type { HandoffRequest } from '@/api/generated/model/handoffRequest'
import type { RibbonResponse } from '@/api/generated/model/ribbonResponse'

export interface HandoffVariables {
  ticketId: string
  data: HandoffRequest
  /**
   * Minted once by the caller before the first attempt — `useReopenMutation`'s
   * own reason applies unchanged: a retried request after a network timeout
   * must be recognised as the same handoff, not a second hop.
   */
  idempotencyKey: string
}

/**
 * `POST /tickets/{ticketId}/handoff`, carrying an idempotency key.
 *
 * Hand-written on `useReopenMutation`'s own precedent: orval drops header
 * parameters, so the generated `useHandoffTicket` sends `Content-Type` alone
 * and has nowhere to put `Idempotency-Key`. Delete this the day orval emits
 * header params — call sites keep working through the generated hook
 * underneath.
 */
export function useHandoffMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationKey: ['handoffTicket'],
    mutationFn: ({ ticketId, data, idempotencyKey }: HandoffVariables) =>
      http<RibbonResponse>({
        url: `/tickets/${ticketId}/handoff`,
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        data,
      }),
    onSuccess: () => {
      // Stage, assignee and status all moved, and the ticket list (and My
      // Tasks) render every one of them — `useReopenMutation`'s identical
      // invalidation, one lifecycle action over.
      void queryClient.invalidateQueries({ queryKey: getListTicketsQueryKey() })
    },
  })
}
