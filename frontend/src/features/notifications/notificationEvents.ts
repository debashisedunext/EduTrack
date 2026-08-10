/**
 * D-043/D-044 · what arrives on the user's own queue.
 *
 * The client half of the payloads `MentionNotifier` (D-052) and
 * `NotificationBroadcaster` (D-044) publish. Parsed rather than cast, for the
 * same reason `destinations.ts` builds destinations rather than accepting
 * strings: **an unrecognised frame must do nothing visible, not something
 * wrong.** A push whose shape drifted after a backend deploy would otherwise
 * raise a toast reading "undefined", which looks like a bug in the feature
 * rather than a contract that moved.
 *
 * These are deliberately *not* generated from the OpenAPI spec — realtime
 * frames are not HTTP responses and appear nowhere in it. If that ever changes,
 * this file is what gets deleted.
 */

/** A notification was raised for this user. Carries enough to render a toast. */
export interface NotificationCreated {
  event: 'notification.created';
  id: number;
  eventCode: string;
  title: string;
  /** Where it happened. Never the message text — see D-052 on why. */
  body: string;
  /** In-app route to open, e.g. `/chat/threads/12`. */
  link: string | null;
}

/** One notification was marked read, possibly in another tab. */
export interface NotificationRead {
  event: 'notification.read';
  id: number;
}

/** The user cleared everything, possibly in another tab. */
export interface NotificationAllRead {
  event: 'notification.all-read';
  count: number;
}

export type NotificationEvent = NotificationCreated | NotificationRead | NotificationAllRead;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/**
 * Narrow a raw frame, or return null if it is not one of ours.
 *
 * Null covers three different situations on purpose — a frame for another
 * feature sharing this queue, a malformed payload, and an event code from a
 * newer deploy — because the handler's response to all three is the same: leave
 * it alone. Chat's `chat.message` rides the same user queue for direct
 * messages, so "not mine" is the common case rather than an error.
 */
export function parseNotificationEvent(payload: unknown): NotificationEvent | null {
  if (!isRecord(payload)) return null;

  switch (payload.event) {
    case 'notification.created':
      // id and title are the two the toast cannot be rendered without; link is
      // legitimately null for a notification with nowhere to go.
      if (typeof payload.id !== 'number' || typeof payload.title !== 'string') return null;
      return {
        event: 'notification.created',
        id: payload.id,
        eventCode: typeof payload.eventCode === 'string' ? payload.eventCode : 'UNKNOWN',
        title: payload.title,
        body: typeof payload.body === 'string' ? payload.body : '',
        link: typeof payload.link === 'string' ? payload.link : null,
      };

    case 'notification.read':
      if (typeof payload.id !== 'number') return null;
      return { event: 'notification.read', id: payload.id };

    case 'notification.all-read':
      if (typeof payload.count !== 'number') return null;
      return { event: 'notification.all-read', count: payload.count };

    default:
      return null;
  }
}
