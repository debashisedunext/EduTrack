import { useMutation, useQueryClient } from '@tanstack/react-query'

import http, { ApiError, newIdempotencyKey } from '@/api/http'
import { getListClientContactsQueryKey } from '@/api/generated/clients/clients'
import type { Contact } from '@/api/generated/model/contact'
import type { ContactWriteRequest } from '@/api/generated/model/contactWriteRequest'

import { CLIENT_KEY } from './clientQueries'

/**
 * B-027 · the three contact writes behind S-33's child grid.
 *
 * Hand-written for the reason `clientQueries.ts` gives beside it: orval omits
 * header parameters, so `Idempotency-Key` has to be set by hand. The **reads**
 * still use the generated `useListClientContacts` — only the writes are here.
 *
 * <h2>Invalidating the client is not optional, and it is the part that would
 * have been easy to leave out</h2>
 *
 * `contactCount` and `hasPrimaryContact` are fields of `ClientDetail`, and
 * `ClientController` derives the `ETag` from that record's `hashCode`. So **every
 * write here moves the client's tag.** A grid that refreshed only the contact
 * list would leave the S-33 form holding a stale tag, and the admin's next Save
 * on the Identity tab would come back `412` — "somebody else saved this client
 * while you were editing", about a change they made themselves, on this screen,
 * seconds earlier.
 *
 * That is also why there is no `If-Match` on the contact routes themselves: the
 * tag would have to come from `listClientContacts`, a collection with no `ETag`
 * of its own. The parent's precondition is the one that catches a stale editor,
 * one level up from the row that changed.
 */

function invalidate(queryClient: ReturnType<typeof useQueryClient>, clientId: number) {
  // Both variants of the list — the grid reads `includeInactive: true` and every
  // picker reads the default, and orval keys them separately. The prefix without
  // params catches both.
  void queryClient.invalidateQueries({ queryKey: getListClientContactsQueryKey(clientId) })
  // The client itself, for the ETag. See the file note.
  void queryClient.invalidateQueries({ queryKey: CLIENT_KEY(clientId) })
  // And the grid, whose primary-contact column and B-028 chip both move.
  void queryClient.invalidateQueries({ queryKey: ['/clients'] })
}

export function useCreateContact(clientId: number) {
  const queryClient = useQueryClient()

  return useMutation<Contact, ApiError, ContactWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: Contact }>({
        url: `/clients/${clientId}/contacts`,
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient, clientId),
  })
}

export function useUpdateContact(clientId: number) {
  const queryClient = useQueryClient()

  return useMutation<Contact, ApiError, { contactId: number; data: ContactWriteRequest }>({
    mutationFn: async ({ contactId, data }) => {
      const body = await http<{ data: Contact }>({
        url: `/clients/${clientId}/contacts/${contactId}`,
        method: 'PATCH',
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient, clientId),
  })
}

/**
 * Removal — which deactivates. `tickets.client_contact_id` points at this row,
 * so the contact stays and stops being offered; the grid still shows them,
 * greyed, because `?includeInactive=true` is what it reads.
 */
export function useRemoveContact(clientId: number) {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, number>({
    mutationFn: async (contactId) => {
      await http<void>({
        url: `/clients/${clientId}/contacts/${contactId}`,
        method: 'DELETE',
      })
    },
    onSuccess: () => invalidate(queryClient, clientId),
  })
}
