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

## Chat is evidence

Messages are immutable after a five-minute window and deletions leave a
tombstone — the row survives, the body is withheld. A thread anybody can quietly
rewrite proves nothing.

This module *publishes* `editableUntil` so the UI knows when to stop offering
"edit". **Enforcing it is D-057**, which is not written yet — there is no edit
or delete endpoint at all today, so nothing can currently violate the rule.

## Not done yet

- **D-051** typing indicators, read receipts. `readBy` is always empty for now.
- **D-052** `@mentions`. The column exists (`mentioned_user_ids`); nothing parses.
- **D-053** attachments. `attachmentIds` is accepted and ignored — it is in the
  contract, and rejecting it would break the generated client.
- **D-055** Ask Status. `MessageKind.STATUS_REQUEST` exists for it.
- **D-057** the edit window and tombstones, as endpoints.
- **Subscriptions are not authorised** — D-013. Chat's *REST* side is scoped by
  membership, but anyone can currently subscribe to any topic and see messages
  broadcast to it. This must not reach real data before D-013.
