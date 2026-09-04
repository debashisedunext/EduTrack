import { http } from 'msw';
import type { Db, ObNotificationRow } from '../db';
import { getDb } from '../db';
import { noContent, notFound, ok, problem, url } from './util';

/**
 * B-112 · OB-13, the onboarding notification centre.
 *
 * **Not a variant of the `/notifications` handlers in `rest.ts`.** The two
 * centres share neither a store nor a tab vocabulary (plan §1.2), and a mock
 * that quietly served both from one array would be the first place that
 * separation stopped being true.
 *
 * ## What is deliberately modelled rather than stubbed
 *
 * Four rules a screen can be built entirely wrong against if the mock waves
 * them through:
 *
 * 1. **Every read is scoped to `currentUserId`.** Two fixture rows belong to
 *    somebody else and must never appear. A mock returning everything means the
 *    first day against the real server is the day the bell turns out empty.
 * 2. **`meta.unreadCount` is the caller's *total*, never the count within the
 *    open tab.** It drives the badge, and a badge that changed as you clicked
 *    between tabs would be reporting something nobody asked about.
 * 3. **An unknown `tab` is a 400**, not a fall-through to `all` — which would
 *    show somebody who mistyped `reminder` everything and look like it worked.
 * 4. **Marking something already read still answers 204.** The caller asked for
 *    a state and it holds; only "not yours, or no such row" is a 404, and it is
 *    a 404 rather than a 403 so it cannot be used to probe which ids exist.
 */

const TABS: Record<string, readonly string[]> = {
  all: [],
  assignments: ['ASSIGNMENT'],
  escalations: ['ESCALATION'],
  reminders: ['REMINDER'],
};

const mine = (db: Db): ObNotificationRow[] =>
  db.obNotifications.filter((n) => n.recipientUserId === db.currentUserId);

const dto = (n: ObNotificationRow) => ({
  id: n.id,
  eventKey: n.eventKey,
  category: n.category,
  title: n.title,
  body: n.body,
  obClientId: n.obClientId,
  journeyId: n.journeyId,
  stepId: n.stepId,
  isRead: n.isRead,
  createdAt: n.createdAt,
  deepLink: n.linkUrl,
});

export const obNotificationHandlers = [
  http.get(url('/onboarding/notifications'), ({ request }) => {
    const db = getDb();
    const query = new URL(request.url).searchParams;

    const tab = query.get('tab') ?? 'all';
    const categories = TABS[tab];
    if (!categories) {
      return problem(400, 'invalid-query', 'Unknown tab', { detail: `No such tab: ${tab}` });
    }

    const cursorRaw = query.get('cursor');
    const cursor = cursorRaw ? Number(cursorRaw) : null;
    if (cursorRaw && !Number.isFinite(cursor)) {
      return problem(400, 'invalid-query', 'Malformed cursor', {
        detail: 'cursor must be one returned in meta.nextCursor',
      });
    }

    const unreadOnly = query.get('unreadOnly') === 'true';
    const limit = Math.min(Number(query.get('limit')) || 25, 100);

    // Newest first, by id — never `createdAt`. One gate opening notifies four
    // owners in the same microsecond, and a cursor over a non-unique key either
    // repeats a row or skips one. Mirrored here so the mock pages the way the
    // server does.
    const rows = mine(db)
      .filter((n) => (cursor == null ? true : n.id < cursor))
      .filter((n) => (unreadOnly ? !n.isRead : true))
      .filter((n) => (categories.length === 0 ? true : categories.includes(n.category)))
      .sort((a, b) => b.id - a.id);

    const page = rows.slice(0, limit);
    const hasMore = rows.length > limit;

    return ok(page.map(dto), {
      nextCursor: hasMore ? String(page[page.length - 1].id) : null,
      hasMore,
      // The total, not the page's and not the tab's.
      unreadCount: mine(db).filter((n) => !n.isRead).length,
    });
  }),

  // Before the `{notificationId}/read` route below: `read-all` is a literal
  // segment sitting where an id would also match, and MSW takes the first
  // handler that does.
  http.patch(url('/onboarding/notifications/read-all'), () => {
    const db = getDb();
    mine(db).forEach((n) => {
      n.isRead = true;
    });
    return noContent();
  }),

  http.patch(url('/onboarding/notifications/:notificationId/read'), ({ params }) => {
    const db = getDb();
    const row = mine(db).find((n) => n.id === Number(params.notificationId));
    if (!row) return notFound('Notification');
    row.isRead = true;
    return noContent();
  }),
];
