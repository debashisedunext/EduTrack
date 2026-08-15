import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'
import { ATTACHMENT_DEFAULT_LIMITS } from '@/components/ui/attachments'
import { toAttachmentLimits, useAttachmentLimits } from './attachmentLimits'

/**
 * C-027 · the client reads §4B.4's caps rather than knowing them.
 *
 * The failure this closes is asymmetric and worth restating: the picker refuses
 * a file **before any request is made**, so a hard-coded 10 MB does not merely
 * disagree with a raised server cap — it overrides it. An administrator lifting
 * the limit to 25 MB would watch the setting save and still be refused at 10 MB,
 * with nothing in the network tab and nothing in any log.
 */

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

describe('toAttachmentLimits', () => {
  it('maps the wire names onto the picker’s', () => {
    // `maxTicketBytes` on the wire, `maxTotalBytes` in the control — the picker
    // predates the endpoint and its prop is not per-ticket by nature.
    expect(toAttachmentLimits({ maxFileBytes: 1, maxTicketBytes: 2, maxFiles: 3, ceilingBytes: 9 })).toEqual({
      maxFileBytes: 1,
      maxTotalBytes: 2,
      maxFiles: 3,
    })
  })

  it('falls back to §4B.4’s published defaults when there is nothing to read', () => {
    expect(toAttachmentLimits(undefined)).toEqual(ATTACHMENT_DEFAULT_LIMITS)
  })

  describe('a nonsense payload costs one limit, not all three', () => {
    it('treats zero as missing rather than as “nothing may be attached”', () => {
      // The dangerous direction. `maxFiles: 0` would disable every upload
      // surface in the product from a perfectly ordinary-looking 200.
      const limits = toAttachmentLimits({ maxFileBytes: 5, maxTicketBytes: 6, maxFiles: 0, ceilingBytes: 5 })

      expect(limits.maxFiles).toBe(ATTACHMENT_DEFAULT_LIMITS.maxFiles)
      expect(limits.maxFileBytes).toBe(5)
      expect(limits.maxTotalBytes).toBe(6)
    })

    it('and so are negatives and non-numbers', () => {
      const limits = toAttachmentLimits({
        maxFileBytes: -1,
        maxTicketBytes: Number.NaN,
        maxFiles: 7,
      } as never)

      expect(limits.maxFileBytes).toBe(ATTACHMENT_DEFAULT_LIMITS.maxFileBytes)
      expect(limits.maxTotalBytes).toBe(ATTACHMENT_DEFAULT_LIMITS.maxTotalBytes)
      expect(limits.maxFiles).toBe(7)
    })
  })

  it('ignores ceilingBytes — the server has already clamped maxFileBytes under it', () => {
    // A client that applied it a second time would be re-deriving a rule it does
    // not own, and would refuse files this server has said it accepts.
    expect(toAttachmentLimits({ maxFileBytes: 25, maxTicketBytes: 50, maxFiles: 3, ceilingBytes: 10 }).maxFileBytes)
      .toBe(25)
  })
})

describe('useAttachmentLimits', () => {
  it('returns what the server is enforcing', async () => {
    server.use(
      http.get('*/attachments/limits', () =>
        HttpResponse.json({
          data: { maxFileBytes: 26214400, maxTicketBytes: 104857600, maxFiles: 30, ceilingBytes: 26214400 },
        }),
      ),
    )

    const { result } = renderHook(() => useAttachmentLimits(), { wrapper })

    await waitFor(() => expect(result.current.maxFileBytes).toBe(26214400))
    expect(result.current.maxTotalBytes).toBe(104857600)
    expect(result.current.maxFiles).toBe(30)
  })

  it('renders a working picker while the request is still in flight', () => {
    // Not a loading state. Blocking on a settings fetch would make a slow
    // request look like a broken attachment control on five screens, and the
    // fallback is the blueprint's own specification rather than a guess.
    const { result } = renderHook(() => useAttachmentLimits(), { wrapper })

    expect(result.current).toEqual(ATTACHMENT_DEFAULT_LIMITS)
  })

  it('and after the request fails', async () => {
    server.use(http.get('*/attachments/limits', () => new HttpResponse(null, { status: 500 })))

    const { result } = renderHook(() => useAttachmentLimits(), { wrapper })

    await waitFor(() => expect(result.current).toEqual(ATTACHMENT_DEFAULT_LIMITS))
  })
})
