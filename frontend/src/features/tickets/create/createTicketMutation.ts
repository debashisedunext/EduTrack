import { useMutation, useQueryClient } from '@tanstack/react-query'
import http from '@/api/http'
import { getListTicketsQueryKey } from '@/api/generated/tickets/tickets'
import type { TicketCreateRequest } from '@/api/generated/model/ticketCreateRequest'
import type { TicketResponse } from '@/api/generated/model/ticketResponse'

/**
 * `POST /tickets`, carrying an idempotency key.
 *
 * This does not use the generated `useCreateTicket`. Orval drops header
 * parameters, so the generated function has no way to send `Idempotency-Key`,
 * and the contract is explicit that a create must: "a retried request after a
 * network timeout is the normal case, not the exception." Without the key, the
 * user who resubmits after a timeout gets two tickets and two ID allocations,
 * and the sequence never gives the first one back.
 *
 * Everything else — URL, types, error shape — still comes from the generated
 * client and the shared mutator, so the only thing hand-written here is the
 * header. Delete this file the day orval emits header params (Stream D owns
 * `orval.config.ts`); the call sites keep working through `useCreateTicket`.
 */
export interface CreateTicketVariables {
  data: TicketCreateRequest
  /**
   * Generated once per logical ticket by the caller, **not** per attempt — a
   * key minted inside the mutation changes on every retry and defends against
   * nothing. See `newIdempotencyKey` in `api/http.ts`.
   */
  idempotencyKey: string
}

export function useCreateTicket() {
  const queryClient = useQueryClient()

  return useMutation({
    // Same key the generated hook uses, so anything observing the mutation
    // cache sees one create regardless of which path issued it.
    mutationKey: ['createTicket'],
    mutationFn: ({ data, idempotencyKey }: CreateTicketVariables) =>
      http<TicketResponse>({
        url: '/tickets',
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        data,
      }),
    onSuccess: () => {
      // Prefix match: the generated key is ['/tickets', params?], so this
      // invalidates every filtered list without enumerating them.
      void queryClient.invalidateQueries({ queryKey: getListTicketsQueryKey() })
    },
  })
}
