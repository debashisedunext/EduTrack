import { describe, expect, it, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import * as React from 'react'

import { realtime, type RealtimeHandler } from '@/realtime/client'

import { useLiveRibbon } from './useLiveRibbon'

/**
 * D-058 · the detail page's half of the live ribbon advance.
 *
 * Stubbed at `realtime.subscribe` rather than at the socket, the pattern
 * `useNotificationStream.test.tsx` established: a real STOMP client in a unit
 * test asserts that a broker works, which is `RealtimeRelayIT`'s job.
 */
let subscribedTo: (string | null)[] = []
let push: RealtimeHandler = () => {
  throw new Error('nothing subscribed')
}

beforeEach(() => {
  subscribedTo = []
  push = () => {
    throw new Error('nothing subscribed')
  }
  vi.spyOn(realtime, 'subscribe').mockImplementation((destination, handler) => {
    subscribedTo.push(destination)
    push = handler
    return () => {}
  })
})

function renderLive(ticketCode: string, ticketId: number | undefined) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidate = vi.spyOn(client, 'invalidateQueries')
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  const view = renderHook(({ id }: { id: number | undefined }) => useLiveRibbon(ticketCode, id), {
    wrapper,
    initialProps: { id: ticketId },
  })
  return { client, invalidate, ...view }
}

/** Whatever was passed to `invalidateQueries`, read structurally. */
type Filters = { predicate?: (q: { queryKey: readonly unknown[] }) => boolean; queryKey?: readonly unknown[] }
type Spy = { mock: { calls: unknown[][] } }

function filtersOf(invalidate: Spy): Filters[] {
  return invalidate.mock.calls.map((call) => call[0] as Filters)
}

/** The predicate the hook hands React Query, applied to one key. */
function matches(invalidate: Spy, key: readonly unknown[]): boolean {
  const call = filtersOf(invalidate).find((arg) => typeof arg?.predicate === 'function')
  if (!call?.predicate) throw new Error('no predicate invalidation was issued')
  return call.predicate({ queryKey: key })
}

describe('the subscription', () => {
  it('does not open a room until the numeric id has arrived', () => {
    renderLive('CRM-26-00347', undefined)

    // The route carries the code; the topic is keyed on the row id, which
    // comes with the detail response. Subscribing before it lands would open
    // a room nobody publishes to — accepted, silent, and dead forever.
    expect(subscribedTo).toEqual([])
  })

  it('subscribes to the ticket topic once the id is known', () => {
    const { rerender } = renderLive('CRM-26-00347', undefined)
    rerender({ id: 347 })

    expect(subscribedTo).toEqual(['/topic/ticket.347'])
  })
})

describe('stage.changed', () => {
  it('invalidates every query for this ticket, whichever tab owns it', () => {
    const { invalidate } = renderLive('CRM-26-00347', 347)
    invalidate.mockClear()

    push({ event: 'stage.changed', ticketId: 347, fromStage: 'DEV', toStage: 'QA' })

    for (const path of ['full', 'journey', 'history', 'effort-logs', 'attachments', 'ribbon']) {
      expect(matches(invalidate, [`/tickets/CRM-26-00347/${path}`])).toBe(true)
    }
  })

  it('leaves another ticket alone, including one whose code is a prefix of this one', () => {
    const { invalidate } = renderLive('CRM-26-00347', 347)
    invalidate.mockClear()

    push({ event: 'stage.changed', ticketId: 347, fromStage: 'DEV', toStage: 'QA' })

    expect(matches(invalidate, ['/tickets/CRM-26-00348/full'])).toBe(false)
    // The trailing slash on the prefix is what makes this false. Without it,
    // a shorter code would invalidate every longer one that starts with it.
    expect(matches(invalidate, ['/tickets/CRM-26-003471/full'])).toBe(false)
  })

  it('refreshes the ticket list, because stage, assignee and status all moved', () => {
    const { invalidate } = renderLive('CRM-26-00347', 347)
    invalidate.mockClear()

    push({ event: 'stage.changed', ticketId: 347, fromStage: 'DEV', toStage: 'QA' })

    const keys = filtersOf(invalidate)
      .map((arg) => arg?.queryKey)
      .filter(Boolean)
    expect(keys.some((key) => String(key?.[0]).startsWith('/tickets'))).toBe(true)
  })

  it('ignores the other traffic on the ticket topic', () => {
    const { invalidate } = renderLive('CRM-26-00347', 347)
    invalidate.mockClear()

    // Comments and typing share this room. Refetching six queries on every
    // keystroke is not what either of them asked for.
    push({ event: 'chat.message', threadId: 9 })
    push({ event: 'typing', userId: 3 })

    expect(invalidate).not.toHaveBeenCalled()
  })

  it('survives a frame that is not an object at all', () => {
    const { invalidate } = renderLive('CRM-26-00347', 347)
    invalidate.mockClear()

    // The broker hands over whatever the publisher sent. A handler that
    // throws is logged and swallowed by the client, which means a malformed
    // frame would silently stop this page updating for the rest of the
    // session — worse than the frame itself.
    expect(() => push(null as never)).not.toThrow()
    expect(invalidate).not.toHaveBeenCalled()
  })
})
