import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { server } from '@/mocks/server'
import { getDb } from '@/mocks/db'
import { useEffortTab } from './useEffortTab'

/**
 * C-061 · the Effort tab's one query.
 *
 * The one thing worth proving against a real request is the reason this hook
 * exists apart from the summary panel's own `detail.cycles` totals: unlike
 * `useAttachmentsTab`, this one *does* forward `cycle` — a page landed on an
 * earlier, sealed cycle through `cycleEffortPath` narrows to that cycle's own
 * log, the same contract `useTicketHistory` follows. Everything else — the
 * grouping, the totals — is plain data `EffortTab.test.tsx` covers without a
 * server in the way.
 */

const TICKET = 'CRM-26-00347'

let requests: URL[] = []
const captureRequest = ({ request }: { request: Request }) => {
  const url = new URL(request.url)
  if (url.pathname.endsWith(`/tickets/${TICKET}/effort-logs`)) requests.push(url)
}

beforeEach(() => {
  requests = []
  getDb().currentUserId = 1
  server.events.on('request:start', captureRequest)
})

afterEach(() => {
  server.events.removeListener('request:start', captureRequest)
})

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

describe('useEffortTab', () => {
  it('asks for every cycle at once when no cycle is selected', async () => {
    const { result } = renderHook(() => useEffortTab({ ticketId: TICKET }), { wrapper })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    const cycles = new Set(result.current.entries.map((e) => e.cycleNo))
    expect(cycles.has(1)).toBe(true)
    expect(cycles.has(2)).toBe(true)
    expect(requests).toHaveLength(1)
    expect(requests[0]!.searchParams.has('cycle')).toBe(false)
  })

  it('narrows to one cycle when the page is scoped to it, unlike the attachments tab', async () => {
    const { result } = renderHook(() => useEffortTab({ ticketId: TICKET, cycle: 1 }), { wrapper })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.entries.every((e) => e.cycleNo === 1)).toBe(true)
    expect(requests[0]!.searchParams.get('cycle')).toBe('1')
  })

  it('does not fetch while the tab is not the active one', async () => {
    renderHook(() => useEffortTab({ ticketId: TICKET, enabled: false }), { wrapper })

    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(requests).toHaveLength(0)
  })

  it('turns a load failure into a message the tab can show', async () => {
    const { result } = renderHook(() => useEffortTab({ ticketId: 'NOPE-00-00000' }), { wrapper })
    await waitFor(() => expect(result.current.loadError).toBeTruthy())
    expect(result.current.entries).toEqual([])
  })
})
