import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { server } from '@/mocks/server'
import { getDb } from '@/mocks/db'
import { useAttachmentsTab } from './useAttachmentsTab'

/**
 * C-060 · the Attachments tab's one query.
 *
 * The one thing worth proving against a real request is the reason this hook
 * exists apart from `TicketAttachmentsSection`'s own `existing` prop: the tab
 * asks for **every** cycle's files, where the strip's `/full` payload is
 * scoped to whichever cycle the page is viewing. Everything else — the
 * client-visible filter, the grouping — is plain data the tab's own tests
 * cover without a server in the way.
 */

const TICKET = 'CRM-26-00347'

let requests: URL[] = []
const captureRequest = ({ request }: { request: Request }) => {
  const url = new URL(request.url)
  if (url.pathname.endsWith(`/tickets/${TICKET}/attachments`)) requests.push(url)
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

describe('useAttachmentsTab', () => {
  it('asks for every cycle at once, not just the one the strip shows', async () => {
    // The seed already carries one cycle-1 file (gateway-500.png); a
    // cycle-scoped request would still find it, so a second file on a
    // different cycle is what actually proves no `cycle` param was sent.
    getDb().attachments.push({
      id: 9001,
      ticketId: TICKET,
      fileName: 'cycle2-file.png',
      contentType: 'image/png',
      sizeBytes: 1000,
      scanStatus: 'CLEAN',
      isClientVisible: false,
      isDeleted: false,
      uploadedById: 3,
      stageCode: 'DEVELOPMENT',
      cycleNo: 2,
      createdAt: '2026-08-09T00:00:00Z',
    })

    const { result } = renderHook(() => useAttachmentsTab({ ticketId: TICKET }), { wrapper })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    const fileNames = result.current.rows.map((r) => r.fileName)
    expect(fileNames).toContain('gateway-500.png')
    expect(fileNames).toContain('cycle2-file.png')
    expect(requests).toHaveLength(1)
    expect(requests[0]!.searchParams.has('cycle')).toBe(false)
  })

  it('does not fetch while the tab is not the active one', async () => {
    renderHook(() => useAttachmentsTab({ ticketId: TICKET, enabled: false }), { wrapper })

    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(requests).toHaveLength(0)
  })

  it('starts with the client-visible filter off, and reports a toggle back', () => {
    const { result } = renderHook(() => useAttachmentsTab({ ticketId: TICKET, enabled: false }), { wrapper })

    expect(result.current.clientVisibleOnly).toBe(false)

    act(() => result.current.setClientVisibleOnly(true))
    expect(result.current.clientVisibleOnly).toBe(true)
  })

  it('turns a load failure into a message the tab can show', async () => {
    const { result } = renderHook(() => useAttachmentsTab({ ticketId: 'NOPE-00-00000' }), { wrapper })
    await waitFor(() => expect(result.current.loadError).toBeTruthy())
    expect(result.current.rows).toEqual([])
  })
})
