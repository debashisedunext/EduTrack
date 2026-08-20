import { ownQueue } from '@/realtime/destinations'
import { ChatThreadKind, type ChatThread } from '@/api/generated/model'

/**
 * D-065 · the §9.3 destination a thread's new messages arrive on.
 *
 * <p>The engine's own rule, from D-050's `destinationsFor`: a ticket thread
 * publishes to `/topic/ticket.{id}`, a project channel to `/topic/project.{id}`,
 * and a direct message to each participant's own queue — because there is no
 * room only those two people are in, and inventing one means either a topic
 * anybody can subscribe to or a second rule for D-013's interceptor to get
 * wrong.
 *
 * Returns `null` when the thread has no destination this client can name, which
 * {@link useRealtime} treats as "do not subscribe". Subscribing to a guessed
 * destination would open a room nobody publishes to and quietly receive
 * nothing, which is worse than not subscribing: it looks live.
 */
export function threadDestination(thread: ChatThread | undefined): string | null {
  if (!thread) return null

  // Only a direct message has a destination this client can name.
  //
  // ⚠️ **The contract does not expose the ids the topics are keyed on**, and
  // that is mine (D-001) rather than a bug here. The engine publishes a ticket
  // thread to `/topic/ticket.{id}` and a project channel to
  // `/topic/project.{id}`, both keyed on the **numeric** row id — and
  // `ChatThread` carries `ticketId` as a `string` (the human code
  // `CRM-26-00347`, per the `TicketId` schema) and no project id at all. So the
  // client cannot address either room.
  //
  // Returning null is the honest answer: `useRealtime` treats it as "do not
  // subscribe", and {@link threadNeedsPolling} turns those threads into a
  // refetch instead, so they still update — just not instantly. A guessed
  // destination would open a room nobody publishes to and receive nothing,
  // which looks live and is not.
  //
  // Raised as a follow-up rather than fixed inside a frontend task: adding a
  // numeric anchor to `ChatThread` is a contract change, a backend change and a
  // regenerated client.
  return thread.kind === ChatThreadKind.DIRECT ? ownQueue() : null
}

/**
 * Whether this thread has to be polled because it cannot be subscribed.
 *
 * <p>The inverse of {@link threadDestination} having an answer. Kept as its own
 * named function rather than inlined as `!destination`, because the two are
 * about to stop being inverses: when the contract carries a numeric anchor,
 * every thread becomes subscribable and this returns false for all of them.
 */
export function threadNeedsPolling(thread: ChatThread | undefined): boolean {
  return thread !== undefined && threadDestination(thread) === null
}

/**
 * Whether a message that arrived on `destination` belongs in `thread`.
 *
 * <p>Only the own-queue case needs asking. A ticket topic carries one ticket's
 * messages and nothing else, but `/user/queue/events` is the delivery path for
 * *everything* addressed to this user — every direct message from anybody, and
 * D-041's notifications besides. Appending on the strength of the subscription
 * alone would file one conversation's message into whichever thread happened to
 * be open.
 */
export function belongsToThread(event: { threadId?: number }, thread: ChatThread | undefined): boolean {
  if (!thread || typeof thread.id !== 'number') return false
  return event.threadId === thread.id
}

/** `TICKET` → `Ticket`, for a heading a person reads rather than a code. */
export function kindLabel(kind: ChatThreadKind | undefined): string {
  switch (kind) {
    case ChatThreadKind.TICKET:
      return 'Ticket threads'
    case ChatThreadKind.DIRECT:
      return 'Direct messages'
    case ChatThreadKind.PROJECT:
      return 'Project channels'
    default:
      return 'Other'
  }
}

/**
 * Threads grouped for the sidebar, in a fixed order.
 *
 * <p>Fixed rather than by most-recent-activity: a list that reorders its own
 * *sections* while you are reading it is how you click the wrong conversation.
 * Threads within a section are ordered by the server, which sorts by last
 * message.
 */
export function groupThreads(threads: ChatThread[]): Array<{ kind: ChatThreadKind; threads: ChatThread[] }> {
  const order: ChatThreadKind[] = [ChatThreadKind.TICKET, ChatThreadKind.DIRECT, ChatThreadKind.PROJECT]
  return order
    .map((kind) => ({ kind, threads: threads.filter((t) => t.kind === kind) }))
    .filter((group) => group.threads.length > 0)
}
