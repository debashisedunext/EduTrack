import { beforeEach, describe, expect, it } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { getDb } from '@/mocks/db'
import { getGetTicketDetailQueryKey, getListTicketsQueryKey } from '@/api/generated/tickets/tickets'

import { useQuickUpdateMutation } from './useQuickUpdateMutation'

/**
 * BUG-002 · a successful quick update left the detail page showing the ticket
 * as it was before the write, because `onSuccess` invalidated the ticket list
 * and nothing else. The detail page reads `/tickets/{id}/full` under its own
 * key, so its cache entry survived untouched until a hard reload.
 *
 * Asserted on the cache rather than on a spy: what matters is that the entry
 * the page reads is marked stale, not which method was called to do it.
 */
const TICKET_CODE = 'CRM-26-00347'
// Anita — the Admin — per `db.ts`'s USERS seed. The mock scopes every ticket
// read by the caller, and the default seeded user is a Developer who cannot
// see this ticket; that is a fact about scoping, not about invalidation.
const ADMIN_USER_ID = 1

beforeEach(() => {
  getDb().currentUserId = ADMIN_USER_ID
})

function harness() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return { queryClient, wrapper }
}

describe('useQuickUpdateMutation', () => {
  it('marks the ticket detail cache stale after a successful update', async () => {
    const { queryClient, wrapper } = harness()
    const detailKey = getGetTicketDetailQueryKey(TICKET_CODE)
    queryClient.setQueryData(detailKey, { data: { ticket: { status: 'REOPENED' } } })
    queryClient.setQueryData(getListTicketsQueryKey(), { data: [] })

    const { result } = renderHook(() => useQuickUpdateMutation(), { wrapper })
    // `mutateAsync` settles the hook's own state, so React wants the await
    // inside `act` — without it the assertion still passes but every run
    // prints an act() warning.
    await act(async () => {
      await result.current.mutateAsync({
        ticketId: TICKET_CODE,
        data: { status: 'AWAITING_INFO' },
        idempotencyKey: 'bug-002-key',
      })
    })

    await waitFor(() => {
      expect(queryClient.getQueryState(detailKey)?.isInvalidated).toBe(true)
    })
    expect(queryClient.getQueryState(getListTicketsQueryKey())?.isInvalidated).toBe(true)
  })

  it('invalidates the cycle the user is looking at, not only the default one', async () => {
    const { queryClient, wrapper } = harness()
    // The detail page keys on `?cycle=` when a cycle is selected, so the key it
    // reads is the base key plus a params object. Invalidating the exact
    // default key alone would leave this one stale.
    const cycleKey = getGetTicketDetailQueryKey(TICKET_CODE, { cycle: 2 })
    queryClient.setQueryData(cycleKey, { data: { ticket: { status: 'REOPENED' } } })

    const { result } = renderHook(() => useQuickUpdateMutation(), { wrapper })
    // `mutateAsync` settles the hook's own state, so React wants the await
    // inside `act` — without it the assertion still passes but every run
    // prints an act() warning.
    await act(async () => {
      await result.current.mutateAsync({
        ticketId: TICKET_CODE,
        data: { status: 'AWAITING_INFO' },
        idempotencyKey: 'bug-002-key-cycle',
      })
    })

    await waitFor(() => {
      expect(queryClient.getQueryState(cycleKey)?.isInvalidated).toBe(true)
    })
  })

  it('leaves another ticket’s detail cache alone', async () => {
    const { queryClient, wrapper } = harness()
    const otherKey = getGetTicketDetailQueryKey('CRM-26-00076')
    queryClient.setQueryData(otherKey, { data: { ticket: { status: 'OPEN' } } })

    const { result } = renderHook(() => useQuickUpdateMutation(), { wrapper })
    // `mutateAsync` settles the hook's own state, so React wants the await
    // inside `act` — without it the assertion still passes but every run
    // prints an act() warning.
    await act(async () => {
      await result.current.mutateAsync({
        ticketId: TICKET_CODE,
        data: { status: 'AWAITING_INFO' },
        idempotencyKey: 'bug-002-key-other',
      })
    })

    expect(queryClient.getQueryState(otherKey)?.isInvalidated).toBe(false)
  })
})
