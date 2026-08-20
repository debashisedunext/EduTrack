import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'

import { SlaSource } from '@/api/generated/model/slaSource'
import { server } from '@/mocks/server'

import { usePlannedCloseDate, type PlannedCloseDateInput } from './usePlannedCloseDate'

/**
 * C-012 · the hook's job is gating and nothing else — the resolution is the
 * server's and is pinned in `mocks.test.ts`.
 *
 * What is tested here is that it never asks a question the server cannot
 * answer. `projectId` and `level` are both required by the contract, so firing
 * without them earns a 400 that would surface as an error under a field the
 * user has not reached yet.
 */

let requests: URL[] = []

/**
 * A **named** listener, added and removed as a pair — the convention
 * `CreateTicketPage.test.tsx` follows.
 *
 * The first draft registered a fresh arrow function in `beforeEach` and cleared
 * the slate with `removeAllListeners`, which works and is still the wrong shape
 * twice over: an anonymous listener cannot be removed, so without the clear it
 * accumulates one per test and every request is recorded four times over; and
 * `removeAllListeners` is a blunt instrument on a module-level object that other
 * suites also listen to. Vitest isolates files, so it could not actually reach
 * them today — but it would the moment anyone turns isolation off, and the
 * failure would be somebody else's suite losing its listener for no visible
 * reason.
 */
const captureRequest = ({ request }: { request: Request }) => {
  const url = new URL(request.url)
  if (url.pathname.endsWith('/tickets/planned-close-date')) requests.push(url)
}

beforeEach(() => {
  requests = []
  server.events.on('request:start', captureRequest)
})

afterEach(() => {
  server.events.removeListener('request:start', captureRequest)
})

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

const inputs: PlannedCloseDateInput = {
  projectId: 1,
  taskTypeId: 2,
  // D-066 · a level is a code from the S-12 master now, not an enum member.
  level: 'HIGH',
  assigneeId: null,
}

describe('usePlannedCloseDate', () => {
  it('does not fire until a project and a level are both chosen', async () => {
    const { result, rerender } = renderHook((props: PlannedCloseDateInput) => usePlannedCloseDate(props), {
      wrapper,
      initialProps: { ...inputs, projectId: null } as PlannedCloseDateInput,
    })

    expect(result.current.isReady).toBe(false)
    expect(result.current.isPending).toBe(false)
    expect(requests).toHaveLength(0)

    rerender({ ...inputs, level: null })
    expect(result.current.isReady).toBe(false)
    expect(requests).toHaveLength(0)

    rerender(inputs)
    await waitFor(() => expect(result.current.preview).not.toBeNull(), { timeout: 4000 })
  })

  it('resolves against the real handler and hands back the whole preview', async () => {
    const { result } = renderHook(() => usePlannedCloseDate(inputs), { wrapper })

    await waitFor(() => expect(result.current.preview).not.toBeNull(), { timeout: 4000 })

    expect(result.current.preview?.source).toBe(SlaSource.PROJECT_TASK_TYPE)
    expect(result.current.preview?.resolutionHrs).toBe(8)
    expect(result.current.preview?.plannedCloseDate).toBeTruthy()
  })

  /**
   * The assignee changes the answer — their approved leave stops the clock — so
   * it has to reach the wire. Dropping it is silent: a date still appears.
   */
  it('sends the optional parameters only when they have values', async () => {
    const { result, rerender } = renderHook((props: PlannedCloseDateInput) => usePlannedCloseDate(props), {
      wrapper,
      initialProps: { ...inputs, taskTypeId: null } as PlannedCloseDateInput,
    })
    await waitFor(() => expect(result.current.preview).not.toBeNull(), { timeout: 4000 })

    expect(requests[0].searchParams.has('taskTypeId')).toBe(false)
    expect(requests[0].searchParams.has('assigneeId')).toBe(false)

    rerender({ ...inputs, assigneeId: 4 })
    await waitFor(() => expect(requests).toHaveLength(2), { timeout: 4000 })

    expect(requests[1].searchParams.get('taskTypeId')).toBe('2')
    expect(requests[1].searchParams.get('assigneeId')).toBe('4')
  })

  /**
   * A 400 or 404 is a statement about the inputs, not a blip — retrying it three
   * times only delays the form settling. The preview is advisory, so a failure
   * degrades to "not shown" rather than blocking the save.
   */
  it('surfaces a failure once, without retrying it', async () => {
    server.use(
      http.get('*/tickets/planned-close-date', () =>
        HttpResponse.json({ title: 'Not found', status: 404 }, { status: 404 }),
      ),
    )

    const { result } = renderHook(() => usePlannedCloseDate(inputs), { wrapper })

    await waitFor(() => expect(result.current.isError).toBe(true), { timeout: 4000 })
    expect(result.current.preview).toBeNull()
    expect(requests).toHaveLength(1)
  })
})
