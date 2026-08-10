import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { Toaster } from '@/components/ui/toaster'
import { useToast } from '@/components/ui/use-toast'
import { realtime } from '@/realtime/client'
import { ownQueue } from '@/realtime/destinations'
import { NotificationStream } from './NotificationStream'

/**
 * D-043/D-044 · the toast and the badge, driven by real frames.
 *
 * The realtime client is stubbed at `subscribe` rather than at the socket: this
 * suite is about what the app does with a frame, and a real STOMP connection
 * would make it a test of SockJS. `RealtimeRelayIT` covers the wire.
 */

/**
 * jsdom implements no Pointer Capture API, and Radix's toast reads it on
 * pointerdown to drive swipe-to-dismiss. Without this, clicking any toast
 * action throws inside an event listener — which does not fail the assertion
 * but does surface as an unhandled error, and vitest exits non-zero on those.
 */
beforeAll(() => {
  const element = Element.prototype as unknown as Record<string, unknown>
  element.hasPointerCapture ??= () => false
  element.setPointerCapture ??= () => {}
  element.releasePointerCapture ??= () => {}
})

/** Pushes a frame to whatever the component subscribed to. */
let push: (payload: unknown) => void
let subscribedTo: string[]

const navigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigate }
})

function renderStream() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <NotificationStream />
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { invalidate }
}

/**
 * Radix renders every toast twice — once visibly and once inside its
 * screen-reader announce region — so each action matches two buttons. Both are
 * the same React element and both fire the handler; this takes the first rather
 * than asserting a count that is really a detail of Radix.
 */
function toastAction(name: RegExp) {
  const matches = screen.getAllByRole('button', { name })
  return matches[0]
}

/** No toast on screen. Keyed on an action, since a toast always has one. */
function noToastShowing() {
  return screen.queryAllByRole('button', { name: /open/i }).length === 0
}

const MENTION = {
  event: 'notification.created',
  id: 91,
  eventCode: 'MENTIONED',
  title: 'Ravi Kumar mentioned you',
  body: 'in CRM-26-00347',
  link: '/chat/threads/12',
}

/**
 * The toast store is a module-level zustand store, so it outlives unmount and
 * carries a previous test's toasts into the next one — which shows up as
 * "found multiple elements" on a title only raised once. Cleared here rather
 * than worked around in the assertions.
 */
function clearToasts() {
  const { result } = renderHook(() => useToast())
  act(() => {
    result.current.toasts.slice().forEach((t) => result.current.dismiss(t.id))
  })
}

beforeEach(() => {
  clearToasts()
  navigate.mockReset()
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

// Belt and braces. A test that installs fake timers and then times out never
// reaches its own cleanup, and the next test inherits them — which turns one
// failure into a run of unrelated five-second timeouts that hide it.
afterEach(() => {
  vi.useRealTimers()
})

describe('the subscription', () => {
  it('listens on the session\'s own queue, never an id-bearing one', () => {
    renderStream()

    // D-013 refuses `/user/{id}/queue/events` on subscribe: Spring resolves the
    // bare form against the session, so naming an id is both unnecessary and
    // the one form the guard rejects.
    expect(subscribedTo).toEqual([ownQueue()])
    expect(subscribedTo[0]).not.toMatch(/\d/)
  })
})

describe('a notification arriving', () => {
  it('toasts the title and where it happened', async () => {
    renderStream()

    push(MENTION)

    expect(await screen.findByText('Ravi Kumar mentioned you')).toBeVisible()
    expect(screen.getByText('in CRM-26-00347')).toBeVisible()
  })

  it('offers Open, Snooze and Dismiss — blueprint §11', async () => {
    renderStream()

    push(MENTION)

    await screen.findAllByText(MENTION.title)
    expect(toastAction(/open/i)).toBeVisible()
    expect(toastAction(/snooze/i)).toBeVisible()
    expect(toastAction(/close/i)).toBeVisible()
  })

  it('refreshes the badge so the bell does not wait for a reload', async () => {
    const { invalidate } = renderStream()

    push(MENTION)

    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith(
        expect.objectContaining({ queryKey: ['/notifications'] }),
      ),
    )
  })

  it('ignores a frame belonging to another feature on the same queue', () => {
    renderStream()

    // Direct-message chat rides this queue too (D-050).
    push({ event: 'chat.message', threadId: 12, body: 'hello' })

    expect(noToastShowing()).toBe(true)
  })

  it('ignores a malformed frame rather than toasting "undefined"', () => {
    renderStream()

    push({ event: 'notification.created', id: 'not-a-number' })

    expect(screen.queryByText(/undefined/i)).not.toBeInTheDocument()
  })
})

describe('Open', () => {
  it('navigates to the notification and marks it read', async () => {
    const user = userEvent.setup()
    renderStream()
    push(MENTION)

    await screen.findAllByText(MENTION.title)
    await user.click(toastAction(/open/i))

    expect(navigate).toHaveBeenCalledWith('/chat/threads/12')
  })
})

describe('Dismiss', () => {
  it('closes the toast without marking it read', async () => {
    const user = userEvent.setup()
    renderStream()
    push(MENTION)

    await screen.findAllByText(MENTION.title)
    await user.click(toastAction(/close/i))

    // The whole point of the bell is to hold what you waved away. A toast that
    // marked itself read on dismiss would empty it of exactly that.
    await waitFor(() => expect(noToastShowing()).toBe(true))
    expect(navigate).not.toHaveBeenCalled()
  })
})

describe('Snooze', () => {
  it('takes the toast away and brings it back later', async () => {
    vi.useFakeTimers()
    renderStream()

    // Everything here is synchronous on purpose. Testing Library's async
    // queries and user-event's internal waits both run on real timers, so
    // under vi.useFakeTimers neither gets a second look and the test dies at
    // its own timeout rather than failing on an assertion. act() plus
    // fireEvent flushes the same state updates without any of that — and the
    // subject here is the ten-minute timer, not pointer semantics.
    act(() => push(MENTION))
    expect(screen.getAllByText(MENTION.title).length).toBeGreaterThan(0)

    act(() => fireEvent.click(toastAction(/snooze/i)))
    expect(noToastShowing()).toBe(true)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10 * 60 * 1000)
    })

    expect(screen.getAllByText(MENTION.title)[0]).toBeVisible()
  })
})

describe('a read landing from another tab', () => {
  it('refreshes the badge without raising a toast', async () => {
    const { invalidate } = renderStream()

    push({ event: 'notification.read', id: 91 })

    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith(
        expect.objectContaining({ queryKey: ['/notifications'] }),
      ),
    )
    expect(noToastShowing()).toBe(true)
  })

  it('refreshes on mark-all-read too', async () => {
    const { invalidate } = renderStream()

    push({ event: 'notification.all-read', count: 12 })

    await waitFor(() => expect(invalidate).toHaveBeenCalled())
    expect(noToastShowing()).toBe(true)
  })
})
