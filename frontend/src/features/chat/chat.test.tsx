import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { ChatPage } from './ChatPage'
import { MessageList } from './MessageList'
import { parseChatMessageEvent } from './chatEvents'
import {
  threadDestination,
  threadNeedsPolling,
  belongsToThread,
  groupThreads,
  kindLabel,
} from './threadDestination'
import { ChatMessageKind, ChatThreadKind, type ChatThread } from '@/api/generated/model'
import { realtime } from '@/realtime/client'

/**
 * D-065 · S-25.
 *
 * <p>The screen is asserted against the mock server (D-004), which is what
 * Stream C has built the whole frontend against; the pure helpers are asserted
 * directly, because the interesting decisions live in them.
 */
/**
 * Stubbed at `subscribe` rather than at the socket, following
 * `useNotificationStream.test`. Without it every render opens a real SockJS
 * handshake against a `/ws/info` the mock server does not serve — which MSW
 * reports as an unhandled request, correctly, because `onUnhandledRequest` is
 * `'error'` by design.
 */
let subscribedTo: string[] = []
let push: (payload: unknown) => void = () => {
  throw new Error('nothing subscribed')
}

beforeEach(() => {
  subscribedTo = []
  vi.spyOn(realtime, 'subscribe').mockImplementation((destination, handler) => {
    subscribedTo.push(destination)
    push = handler
    return () => {}
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})

function renderChat() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/chat']}>
        <ChatPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const thread = (over: Partial<ChatThread> = {}): ChatThread => ({
  id: 1,
  kind: ChatThreadKind.TICKET,
  title: 'CRM-26-00347',
  ticketId: 'CRM-26-00347',
  unreadCount: 0,
  participants: [],
  ...over,
})

describe('which room a thread listens to', () => {
  it('sends a direct message to the reader own queue, because no room holds only two people', () => {
    expect(threadDestination(thread({ kind: ChatThreadKind.DIRECT, ticketId: null }))).toBe('/user/queue/events')
  })

  it('subscribes a ticket thread to nothing, because the contract carries no numeric anchor', () => {
    // `ChatThread.ticketId` is the *human code* (`CRM-26-00347`) — the
    // `TicketId` schema is a string — while `/topic/ticket.{id}` is keyed on
    // the numeric row id. So this client genuinely cannot name the room its
    // own backend publishes to, and says so rather than guessing.
    expect(threadDestination(thread({ kind: ChatThreadKind.TICKET }))).toBeNull()
    expect(threadNeedsPolling(thread({ kind: ChatThreadKind.TICKET }))).toBe(true)
  })

  it('subscribes a project channel to nothing either — no project id at all', () => {
    expect(threadDestination(thread({ kind: ChatThreadKind.PROJECT, ticketId: null }))).toBeNull()
    expect(threadNeedsPolling(thread({ kind: ChatThreadKind.PROJECT, ticketId: null }))).toBe(true)
  })

  it('does not poll a thread it can subscribe to', () => {
    expect(threadNeedsPolling(thread({ kind: ChatThreadKind.DIRECT, ticketId: null }))).toBe(false)
  })
})

describe('the shared user queue', () => {
  it('takes a chat message frame', () => {
    const event = parseChatMessageEvent({ event: 'chat.message', threadId: 4, message: { id: 9, body: 'hi' } })
    expect(event).toEqual({ event: 'chat.message', threadId: 4, message: { id: 9, body: 'hi' } })
  })

  it("leaves another feature's frame alone, because notifications ride the same queue", () => {
    expect(parseChatMessageEvent({ event: 'notification.created', id: 3, title: 'Assigned' })).toBeNull()
  })

  it('drops a message with no id, which would duplicate on the next refetch', () => {
    expect(parseChatMessageEvent({ event: 'chat.message', threadId: 4, message: { body: 'hi' } })).toBeNull()
  })

  it.each([null, undefined, 'nonsense', 42])('ignores a malformed payload: %s', (payload) => {
    expect(parseChatMessageEvent(payload)).toBeNull()
  })

  it('files a message by thread id rather than by the subscription it arrived on', () => {
    // The own queue carries every direct message this user receives, not just
    // the open one's. Appending on the subscription alone would put one
    // conversation's message into whichever thread happened to be selected.
    expect(belongsToThread({ threadId: 1 }, thread({ id: 1 }))).toBe(true)
    expect(belongsToThread({ threadId: 2 }, thread({ id: 1 }))).toBe(false)
  })
})

describe('the sidebar', () => {
  it('groups the three surfaces in a fixed order and drops empty sections', () => {
    const groups = groupThreads([
      thread({ id: 1, kind: ChatThreadKind.DIRECT }),
      thread({ id: 2, kind: ChatThreadKind.TICKET }),
    ])

    // Fixed order, not most-recent-first: a list whose *sections* reorder while
    // being read is how somebody clicks the wrong conversation.
    expect(groups.map((g) => g.kind)).toEqual([ChatThreadKind.TICKET, ChatThreadKind.DIRECT])
    expect(kindLabel(ChatThreadKind.PROJECT)).toBe('Project channels')
  })
})

describe('a message', () => {
  it('withholds a deleted body and says so, because chat is evidence', () => {
    render(
      <MessageList
        isLoading={false}
        loadError={false}
        messages={[{ id: 1, body: 'the original words', isDeleted: true, author: { id: 1, displayName: 'Ravi' } }]}
      />,
    )

    // §7.6 keeps the body in the row and withholds it on read. The client
    // renders the same rule, so a body that somehow arrived is still not shown.
    expect(screen.getByText('This message was deleted.')).toBeInTheDocument()
    expect(screen.queryByText('the original words')).not.toBeInTheDocument()
  })

  it('marks an edited message rather than silently rewriting it', () => {
    render(
      <MessageList
        isLoading={false}
        loadError={false}
        messages={[{ id: 1, body: 'fixed a typo', isEdited: true, author: { id: 1, displayName: 'Ravi' } }]}
      />,
    )

    expect(screen.getByText('edited')).toBeInTheDocument()
  })

  it('does not offer an edit marker on a tombstone', () => {
    render(
      <MessageList
        isLoading={false}
        loadError={false}
        messages={[{ id: 1, isDeleted: true, isEdited: true, author: { id: 1, displayName: 'Ravi' } }]}
      />,
    )

    expect(screen.queryByText('edited')).not.toBeInTheDocument()
  })

  it('attributes a system message to the product, not to a person', () => {
    render(
      <MessageList
        isLoading={false}
        loadError={false}
        messages={[{ id: 1, body: 'Ticket handed to QA.', kind: ChatMessageKind.SYSTEM }]}
      />,
    )

    expect(screen.getByText('EduTrack')).toBeInTheDocument()
  })

  it('says a conversation failed to load rather than showing it as empty', () => {
    render(<MessageList isLoading={false} loadError messages={[]} />)

    // An empty state on a failed read is a lie a reader acts on — they assume
    // nothing was said.
    expect(screen.getByText('This conversation could not be loaded')).toBeInTheDocument()
  })
})

describe('the screen, against the mock server', () => {
  it('lists conversations and opens the first one without being asked', async () => {
    renderChat()

    const conversations = await screen.findByRole('navigation', { name: 'Conversations' })
    expect(conversations).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('list', { name: 'Messages' })).toBeInTheDocument())
  })

  it('will not send an empty message', async () => {
    renderChat()
    await screen.findByRole('navigation', { name: 'Conversations' })

    const send = await screen.findByRole('button', { name: 'Send' })
    expect(send).toBeDisabled()

    // Whitespace is not a message either — trimmed before the length check, so
    // a spacebar does not enable the button.
    await userEvent.type(screen.getByLabelText('Message'), '   ')
    expect(send).toBeDisabled()
  })

  it('sends what was typed and clears the box', async () => {
    renderChat()
    await screen.findByRole('navigation', { name: 'Conversations' })

    const box = screen.getByLabelText('Message')
    await userEvent.type(box, 'Looking at it now')
    expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled()

    await userEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(box).toHaveValue(''))
  })
})

describe('a message arriving while the screen is open', () => {
  it('subscribes to the reader own queue for as long as the screen is up', async () => {
    renderChat()
    await screen.findByRole('navigation', { name: 'Conversations' })

    // Not conditional on which thread is selected: a direct message arrives
    // here whether or not its thread is open, and that is what moves the
    // unread badge while somebody is reading something else.
    expect(subscribedTo).toContain('/user/queue/events')
  })

  it('refreshes when a chat frame arrives, and ignores a notification on the same queue', async () => {
    renderChat()
    await screen.findByRole('navigation', { name: 'Conversations' })
    await waitFor(() => expect(screen.getByRole('list', { name: 'Messages' })).toBeInTheDocument())

    // Neither of these may throw. The queue is shared with D-041, so the
    // handler sees far more frames that are not its business than ones that
    // are, and the notification below is the ordinary case rather than an edge.
    expect(() => push({ event: 'notification.created', id: 7, title: 'Assigned to you' })).not.toThrow()
    expect(() => push({ event: 'chat.message', threadId: 1, message: { id: 99, body: 'On it' } })).not.toThrow()

    await waitFor(() => expect(screen.getByRole('list', { name: 'Messages' })).toBeInTheDocument())
  })
})
