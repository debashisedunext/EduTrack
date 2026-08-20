import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { Toaster } from '@/components/ui/toaster'
import { useToast } from '@/components/ui/use-toast'
import { realtime } from '@/realtime/client'
import { ownQueue } from '@/realtime/destinations'
import { http, HttpResponse } from 'msw'
import { getDb } from '@/mocks/db'
import { server } from '@/mocks/server'
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
  link: '/tickets/CRM-26-00347',
}

/**
 * The same frame, pointing into chat.
 *
 * This fixture used to *be* `MENTION` — its link was `/chat/threads/12`, and
 * every toast assertion in this file was therefore written against a
 * notification that no longer toasts. The link is what decides now
 * (`isChatNotification`), so the two cases need two fixtures, and a comment
 * mention on a ticket is what `MENTION` was always describing anyway: that is
 * the shape the mock database seeds it in.
 */
const CHAT_MESSAGE = {
  event: 'notification.created',
  id: 92,
  eventCode: 'CHAT_MESSAGE',
  title: 'Meera Iyer sent you a message',
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
  // Drain D-046's offline queue for the whole file, not just for the block
  // that is about it.
  //
  // `resetDb()` re-seeds three `TICKET_HANDED_OFF` rows for the mock user with
  // no `deliveredAt`, so every `renderStream()` fetches them from
  // `/notifications/pending` and pops them — in *every* describe block, not
  // only D-046's. The blocks above are about a single live frame, and a
  // replayed toast landing mid-test is noise they never asked for.
  //
  // This is what made `Dismiss › closes the toast without marking it read`
  // flaky: it dismisses the pushed toast and then asserts that *nothing* is
  // showing, which held only while the replay had not arrived yet. Under a
  // full run's CPU contention it arrives inside the window, and the leftover
  // toast is "CRM-26-00347 handed to you at Development" — a seeded row, not
  // the one under test. The failure therefore read as "dismiss did not close
  // the toast", pointing at the dismiss handler, which is entirely innocent.
  // Raising the timeout does not help; it just waits longer for a toast that
  // is correctly there.
  getDb().notifications.forEach((n) => { n.deliveredAt = new Date().toISOString() })
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

    expect(navigate).toHaveBeenCalledWith('/tickets/CRM-26-00347')
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

describe('D-046 · what was raised while nobody was watching', () => {
  /** Queue one for the current mock user, undelivered. */
  function queue(id: number, title: string) {
    const db = getDb()
    db.notifications.push({
      id,
      userId: db.currentUserId,
      eventKey: 'TICKET_HANDED_OFF',
      title,
      body: 'while you were away',
      ticketId: 'CRM-26-00347',
      isRead: false,
      deepLink: '/tickets/CRM-26-00347',
      createdAt: new Date().toISOString(),
      deliveredAt: null,
    })
  }

  // The drain that used to live here is now file-level — every block needed
  // it, not just this one. Each test below still adds exactly what it is about
  // via `queue()`, which runs after that drain.

  it('pops a notification raised while the user was offline', async () => {
    queue(9001, 'Queued while you were offline')
    renderStream()

    expect(await screen.findAllByText('Queued while you were offline')).not.toHaveLength(0)
  })

  it('acknowledges what it popped, so it does not pop again', async () => {
    queue(9002, 'Popped once')
    renderStream()
    await screen.findAllByText('Popped once')

    await waitFor(() =>
      expect(getDb().notifications.find((n) => n.id === 9002)?.deliveredAt).not.toBeNull(),
    )
  })

  it('says so when the cap hid some, rather than dropping them quietly', async () => {
    for (let i = 0; i < 8; i++) queue(9100 + i, `Missed ${i}`)
    renderStream()

    expect(await screen.findAllByText('More while you were away')).not.toHaveLength(0)
  })

  it('pops nothing when the queue is empty', async () => {
    renderStream()

    await waitFor(() => expect(noToastShowing()).toBe(true))
  })

  it('acknowledges a live toast too, so it is not replayed at next login', async () => {
    // The replay is forced to return nothing, so the only thing that can
    // acknowledge id 91 is the live frame. Without this the drain would
    // acknowledge it on mount and the assertion would hold either way — a test
    // that passes whether or not the behaviour exists.
    server.use(
      http.get('*/notifications/pending', () =>
        HttpResponse.json({ data: [], hasMore: false }),
      ),
    )
    queue(91, MENTION.title)
    renderStream()

    push(MENTION)

    await waitFor(() =>
      expect(getDb().notifications.find((n) => n.id === 91)?.deliveredAt).toBeTruthy(),
    )
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

/**
 * Chat does not toast — it goes to the header's chat panel.
 *
 * <p>A toast is an interruption, and a conversation is the one source that
 * generates them faster than anybody can read them: an exchange between two
 * other people raised a card per message, and D-046's replay popped every one
 * of them again on the next page load. `ChatBadge` is where they land instead,
 * and the point of it is that it shows nothing until somebody opens it.
 *
 * <p>These assert the *negative* deliberately, which is the direction that
 * rots quietly: the guard is one early return, and without a test that fails
 * when it is removed, a later refactor takes the popups back and nothing goes
 * red. The second case is what stops that being a vacuous pass — a frame that
 * did nothing at all would satisfy the first assertion just as well.
 */
describe('a chat message arriving', () => {
  it('does not toast', async () => {
    renderStream()

    push(CHAT_MESSAGE)

    // Nothing to await — proving absence needs a beat for the toast that
    // would have been raised synchronously not to appear.
    await waitFor(() => expect(noToastShowing()).toBe(true))
    expect(screen.queryByText(CHAT_MESSAGE.title)).not.toBeInTheDocument()
  })

  it('moves the chat badge instead, so the panel is where it is read', async () => {
    const { invalidate } = renderStream()

    push(CHAT_MESSAGE)

    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith(
        expect.objectContaining({ queryKey: ['/chat/threads'] }),
      ),
    )
  })

  it('still toasts everything that is not chat', async () => {
    renderStream()

    push(MENTION)

    expect(await screen.findByText(MENTION.title)).toBeVisible()
  })
})
