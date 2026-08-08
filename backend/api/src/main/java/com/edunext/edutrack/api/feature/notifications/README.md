# feature/notifications

**Owner: Stream D · Debashis**

Notification centre, preference matrix, browser push. Screen S-26.

## The centre (D-041)

`GET /notifications` · `PATCH /notifications/{id}/read` · `PATCH /notifications/read-all`

**The bell dropdown and the full page are the same endpoint.** "Last 10" is a
`limit`, not a second route — two routes would mean two places to keep the tab
filter honest.

### The badge counts every unread, not the unread in the open tab

`meta.unreadCount` is always the caller's total. A badge that changed as you
clicked between tabs would be reporting something nobody asked about, and the
bell is read on every page load by every user — it must mean one thing.

### Tabs are a view over event categories

| Layer | Where | Why there |
|---|---|---|
| `NotificationEvent` + `Category` | `domain/notifications` | The worker raises events too; scanners must not import an api package |
| `NotificationTab` | here | Tabs are a decision about one screen. Renaming a tab must not touch a scanner |

The split between Assignments and Escalations is by **why the event exists**,
not by who receives it: an assignment changes who is responsible or what is
expected of them; an escalation exists because something is late, failed or got
worse. That rule decides the awkward cases — "QA failed, sent for rework" hands
work back to a developer, so it is an assignment, while "deployment failed"
reports a failure to three people, so it is an escalation.

**Not every event has a tab.** `Category.OTHER` — a comment, an attachment, the
daily digest, a bounced mail — is worth a bell entry and not worth a tab. Those
appear under All, which is what All is for. Inventing tabs S-26 does not have
would be a change to the screen rather than to the enum.

### Strict on write, tolerant on read

`NewNotification` takes a `NotificationEvent`, not a string, because a misspelt
code would land the notification in All and nowhere else — silently, and
forever. Reading goes through `NotificationEvent.of`, which returns empty rather
than throwing: a row written by a newer deploy, or by a code since retired, must
still render. **Losing the notification is worse than losing its tab.**

### Smaller decisions worth not re-litigating

- **Ordering and paging are by `id`, never `created_at`.** Two notifications from
  one fan-out share a microsecond, and a cursor over a non-unique key either
  repeats a row or skips one.
- **`is_read = 0` is in the `UPDATE`'s WHERE**, so re-reading does not restamp
  `read_at` — otherwise "when did you see this" becomes "when did you last open
  the list".
- **Already-read answers 204, same as just-marked.** The caller asked for a
  state and it holds. Only "not yours or no such row" is a 404 — and it is a 404
  rather than a 403, so it cannot be used to test which ids exist.
- **An unrecognised `tab` is a 400**, not a fall-through to All, which would show
  somebody who mistyped `mention` everything and look like it worked.
- **`mark-all-read` ignores the open tab.** The contract takes no tab there, and
  a "mark all read" that left some unread is a lie the badge contradicts a
  second later.

## Producers today

| Event | Raised by | Task |
|---|---|---|
| `MENTIONED` | `feature/chat/MentionNotifier` | D-052 |
| `MAIL_DELIVERY_FAILED` | `worker/outbox/MailFailureNotifier` | D-033 |
| `EMAIL_ADDRESS_SUPPRESSED` | `webhook/BounceWebhookController` | D-034 |

Every other constant in `NotificationEvent` is named and unwired. **D-040 owns
the producers**; what is fixed here is only the spelling, so the tabs and the
producers cannot disagree about it.

## Not done yet

- **D-040** the remaining ~22 events — triggers, recipients, templates.
- **D-042** the per-user preference matrix. Until it exists, no optional mail is
  sent for an event §4B.6 marks optional — see `feature/chat` on why mention
  email waits for it.
- **D-043** in-app toast. `MentionNotifier` already pushes
  `notification.created` to the recipient's queue carrying the notification id,
  which is what Open / Snooze / Dismiss need to act on.
- **D-044** the persistent badge. **Nothing pushes a `notification.read` event**,
  so marking one read in a second tab leaves the first tab's badge stale until
  it reloads. That belongs with the badge, not here.
- **D-046** offline queueing and replay on next login.
