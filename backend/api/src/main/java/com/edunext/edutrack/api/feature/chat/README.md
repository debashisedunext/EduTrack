# feature/chat

**Owner: Stream D · Debashis**

Blueprint §7.6 — three surfaces, one engine.

| Surface | Anchor | Broadcasts to |
|---|---|---|
| Ticket thread | `ticket_id` | `/topic/ticket.{id}` |
| Project channel | `project_id` | `/topic/project.{id}` |
| Direct message | neither | `/user/{id}/queue/events`, one per participant |

`ChatService.destinationsFor` is the *only* place the three differ. Everything
else — posting, paging, membership, unread counts, the edit window — runs the
same code for all three. If you find yourself adding a `switch` on `ChatKind`
anywhere else, that is the signal the engine is splitting into three.

## Why a DM fans out to queues instead of a topic

There is no room only those two people are in. Creating one would mean either a
topic anyone could subscribe to, or a second authorisation rule for D-013 to get
right — and the one that is wrong is the one that leaks a private conversation.

## Membership is the authorisation

Every entry point goes through `threadForParticipant`, which returns empty both
for a thread that does not exist and for one you are not in. The controller
turns either into **404, never 403**. A 403 on a direct message confirms that
two named people are talking, which is the private part.

This does not wait for A-034: chat membership is explicit in
`chat_participants`, so it needs no role reasoning.

## Chat is evidence (D-057)

Messages are immutable after a five-minute window and deletions leave a
tombstone. A thread anybody can quietly rewrite proves nothing.

| Rule | Where it is enforced |
|---|---|
| Author only | `sender_id = :userId` in the SQL, not in Java |
| Five-minute window | `created_at > NOW(6) - INTERVAL 5 MINUTE`, **in the database** |
| Deleted stays deleted | `deleted_at IS NULL` guard on both statements |
| Body is never destroyed | `deleted_at` is set; `body` is left intact |

**The window is evaluated by the database on purpose.** An application clock
makes five minutes mean something slightly different on every instance, and a
message that is editable on one pod and frozen on another is the kind of
inconsistency that only appears in production.

It is checked twice — once to decide the response, once in the `UPDATE`'s `WHERE`
clause. That is not redundant caution: they are two statements, and a later
refactor that reorders them still cannot widen the window.

**Deletion has no time limit, and that is not an inconsistency.** The window
exists so nobody can rewrite what was said; a tombstone *adds* to the record
rather than altering it. The body stays in the database and is withheld on read.

Deletion is author-only. Moderator deletion is not in §7.6, and an authority to
erase other people's words is not a gap to fill quietly.

**404, not 403, when it is not yours.** A conflict answer would confirm the
message exists and that someone else wrote it. `409` is reserved for *your own*
message that has passed the window — the conflict is with the resource's state,
not with your authority over it.

## Receipts and typing (D-051)

**Read receipts are derived, not stored.** "Who has read message 42" is "whose
cursor is at least 42", so the existing `last_read_message_id` answers it with
one query per page. A row per reader per message would be the same information
at hundreds of times the write cost.

The author is excluded from their own `readBy` — a UI that renders it puts your
avatar on every message you send.

`chat.read` is broadcast **only when the cursor actually moves**. Re-opening a
thread you have already read would otherwise spray a receipt at everyone each
time you glanced at it.

**Typing is the one part of chat that persists nothing.** It is true for about
two seconds and worthless afterwards, and a row per keystroke burst does not
belong in a table that exists to hold evidence. If the socket drops the
indicator simply stops, which is the correct behaviour anyway.

It is still membership-checked: an unauthorised typing event leaks that a thread
exists and who is active in it. Smaller than leaking a message, not absent.

> **Unverified:** typing is the first consumer of the `/app` prefix, and whether
> a `Principal` actually reaches a STOMP message under `dev-noauth` has **not**
> been proven end to end — the test profile has no such filter, so an IT would
> exercise the null path, not the real one. The handler fails closed and logs
> when the principal is missing, so the failure is visible rather than silent.
> Confirm this when D-013 wires socket authentication properly.

## Not done yet

- **D-052** `@mentions`. The column exists (`mentioned_user_ids`); nothing parses.
- **D-053** attachments. `attachmentIds` is accepted and ignored — it is in the
  contract, and rejecting it would break the generated client.
- **D-055** Ask Status. `MessageKind.STATUS_REQUEST` exists for it.
- **Subscriptions are not authorised** — D-013. Chat's *REST* side is scoped by
  membership, but anyone can currently subscribe to any topic and see messages
  broadcast to it. This must not reach real data before D-013.
