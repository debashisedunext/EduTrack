import { useQueryClient } from '@tanstack/react-query'

import { useSkipStage } from '@/api/generated/ribbon/ribbon'
import { getListTicketsQueryKey } from '@/api/generated/tickets/tickets'

/**
 * `POST /tickets/{ticketId}/skip-stage` — the generated `useSkipStage`, with
 * one addition: invalidating the ticket list (and My Tasks) on success,
 * `useReopenMutation`'s own reason for the identical addition — stage,
 * assignee and status all moved, and every list that renders them needs to
 * refetch. Unlike `useHandoffMutation`, this route takes no
 * `Idempotency-Key` header — `contracts/openapi.yaml`'s `skipStage`
 * operation does not declare one — so there is nothing orval drops that
 * needs hand-rolling here; the generated hook is used directly rather than
 * wrapped in a second `useMutation`.
 */
export function useSkipStageMutation() {
  const queryClient = useQueryClient()

  return useSkipStage({
    mutation: {
      mutationKey: ['skipStageTicket'],
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: getListTicketsQueryKey() })
      },
    },
  })
}
