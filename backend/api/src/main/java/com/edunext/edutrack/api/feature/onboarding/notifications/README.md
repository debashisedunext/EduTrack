# feature/onboarding/notifications

**Owner: Stream B · Ayush** — PHASE-2-BUILD-PLAN.md §210 assigns this package
and the notification centre to Stream B.

OB-13. The bell popover and the full history page.

## The routes (B-112)

`GET /onboarding/notifications` · `PATCH /onboarding/notifications/{id}/read` ·
`PATCH /onboarding/notifications/read-all`

**The popover and the page are the same endpoint.** PHASE-2-BUILD-PLAN.md §73
asked for both surfaces — "the popover is the daily surface; a full page is
needed for history and for the digest links to land somewhere" — and they are
two renderings with a different `limit`, not two routes. Two would mean two
places to keep the tab filter and the badge honest.

## Why this is not a `module` parameter on `/notifications`

The obvious design was a filter on Stream D's S-26 route. Three things stopped
it, and none of them is ownership:

| | |
|---|---|
| **The event vocabulary** | `NewNotification` takes a `NotificationEvent`, not a string, so every onboarding event would have to be declared in Stream D's enum — the cross-stream edit B-110 and B-111 both declined, and the one `ObNotificationEvent` exists to avoid |
| **The drill-down** | `notifications` carries `ticket_id` and nothing else. An onboarding entry is about a client, a journey and a step |
| **The tabs** | S-26 groups by Mentions / Assignments / Escalations / Status requests. Two of those have no onboarding meaning, and this module has one they lack — a TAT reminder is its commonest notification and would land in "everything else" |

Underneath, a query parameter would have had to fan out to two tables anyway.
Plan §1.2's separability, followed through.

## Staff only, and that is a scope line rather than a gap

`ob_notifications.recipient_user_id` is a `users` id and is never null. §9's
portal screens (CP-01…CP-07) list no notification centre, so an entry addressed
to a client contact would be a notification with nowhere to appear — worse than
none, because whoever queued it believes the client was told.

`InAppChannelAdapter` refuses such a row permanently with a reason, and B-110's
failure notice raises it to the Admins. Visible beats silent. When the portal
earns a surface it gets its own store and its own visibility rules, decided
then — a client's notifications must never be one `WHERE` clause away from a
staff member's, which is the argument `ObMailAudience` already makes for
wording.

## Reading here, writing in the worker

There is no writer in this package, and that is deliberate. The one thing that
writes a bell entry is `worker/onboarding/outbox/InAppChannelAdapter`, draining
the queue; **anything that wants a bell entry enqueues an `IN_APP` row** through
`ObOutboxEnqueuer`, which is how a notification comes to commit with the
business change that caused it — and how it inherits the queue's dedupe,
ordering and retry.

Two classes rather than one is not an accident: the dispatcher writes without
ever listing, and this screen lists without ever writing.

## Smaller decisions worth not re-litigating

- **Ordering and paging are by `id`, never `created_at`.** One gate opening
  notifies four owners in the same microsecond, and a cursor over a non-unique
  key either repeats a row or skips one.
- **`meta` composes `common.pagination.PageMeta`** rather than declaring its own
  `nextCursor` — A-053's `PaginationRulesTest` refuses a sixth such record, and
  the near miss here is that the obvious model, `NotificationDtos.Meta`, *is*
  the grandfathered fifth. `@JsonUnwrapped` keeps the wire shape flat.
- **The cursor is a plain row id**, matching `/notifications` rather than
  A-053's base64 envelope. Two notification centres whose cursors look different
  on the wire would be a difference with nothing behind it.
- **`meta.unreadCount` is the caller's total**, never the count within the open
  tab. The badge is read on every page load and must mean one thing.
- **`is_read = 0` is in the `UPDATE`'s WHERE**, so re-reading does not restamp
  `read_at` — otherwise "when did you see this" becomes "when did you last open
  the list".
- **Already-read answers 204, same as just-marked.** Only "not yours or no such
  row" is a 404, and it is a 404 rather than a 403 so it cannot be used to test
  which ids exist.
- **An unrecognised `tab` is a 400**, not a fall-through to All, which would
  show somebody who mistyped `reminder` everything and look like it worked.
- **`mark-all-read` ignores the open tab.** The contract takes none there, and a
  "mark all read" that left some unread is a lie the badge contradicts a second
  later.

## Auth

`isAuthenticated()`, and nothing more. Blueprint §2 grants no notification
capability because receiving notifications is not one — the same reasoning that
puts `/notifications` on every role. Every statement in the repository is scoped
to `recipient_user_id`, so a platform role with no onboarding access reaches the
route and gets an empty list.

The module gate that would make it a 404 instead is A-111's `ModuleAccessGuard`,
still written-but-unwired; see `ObJourneyTemplateController`'s class javadoc,
which states the same interim position for OB-07.

## Not done yet

- **No realtime.** S-26 pushes over `NotificationBroadcaster` (D-043/D-044); this
  centre refetches. Publishing to a recipient's queue is `api/realtime/`, Stream
  D's, and an onboarding destination is theirs to name.
- **No preference matrix.** D-042's equivalent for onboarding events belongs
  with B-113's OB-11/OB-12 settings, which is where the escalation matrix and
  the TAT thresholds become configuration.
- **The bell does not mount anywhere yet.** `ObNotificationBell` is finished and
  tested; the onboarding shell that carries it is B-108/B-109. The page is
  routed at `/onboarding/notifications`, which is where B-114's digest links
  land.
