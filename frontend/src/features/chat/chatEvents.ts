import type { ChatMessage } from '@/api/generated/model'

/**
 * D-065 · the frame `ChatService.broadcast` publishes for a new message.
 *
 * <p>Shape taken from the engine: `{"event": "chat.message", "threadId": …,
 * "message": {…}}`.
 */
export interface ChatMessageEvent {
  event: 'chat.message'
  threadId: number
  message: ChatMessage
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/**
 * Narrow a raw frame, or return null if it is not a chat message.
 *
 * <p>Null is the common case rather than an error, and deliberately covers
 * three situations with one answer — ignore it. `/user/queue/events` is shared:
 * D-041's notifications ride it, and so does every direct message addressed to
 * this user. A frame for another feature, a malformed payload and an event
 * code from a newer deploy all mean the same thing to this handler.
 *
 * <p>This mirrors `parseNotificationEvent`, which says the same of chat from
 * the other side. Two parsers on one queue, each ignoring the other's traffic,
 * is the arrangement — not a shared dispatcher, because that would make every
 * feature's event shape everybody's business.
 */
export function parseChatMessageEvent(payload: unknown): ChatMessageEvent | null {
  if (!isRecord(payload)) return null
  if (payload.event !== 'chat.message') return null
  if (typeof payload.threadId !== 'number') return null
  if (!isRecord(payload.message)) return null

  // The id is the one field an append cannot be done without: it is the React
  // key and the dedupe key. A frame missing it is dropped rather than rendered
  // with a synthetic one, which would duplicate on the next refetch.
  if (typeof payload.message.id !== 'number') return null

  return {
    event: 'chat.message',
    threadId: payload.threadId,
    message: payload.message as ChatMessage,
  }
}
