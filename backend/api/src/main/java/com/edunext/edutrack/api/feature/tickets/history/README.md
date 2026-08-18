# History — blueprint §7/§4B.5

`GET /tickets/{ticketId}/history`, cycle-grouped, optionally interleaved with
comments. The write side already exists — A-040/A-042's `TicketJournal`, and
quick-update, priority-change and reopen already append through it — so this
is a read path, not a new way to mutate `ticket_history`. **No edit or delete
route exists for this path, and none is added here.**

## What is here

| File | |
|---|---|
| `TicketHistoryController` | `GET /tickets/{ticketId}/history` |
| `TicketHistoryService` | merges the journal with comments, cursor-pages the result |
| `TicketHistoryUserRefs` | `actor_id`/`author_id` → `UserRef`, a near-twin of `EffortLogUserRefs` |
| `TicketHistoryDtos` | the contract's `HistoryEntry`, field for field — minus the hash |

## `include=comments` reads `TicketCommentRepository` directly, not `CommentService`

`CommentService` and its DTOs are package-private, in `comments/`, and return
a shape (`CommentDtos.CommentDto`) this package cannot see. Rather than
widening that feature's surface as a side effect of this one,
`TicketHistoryService` calls
`TicketCommentRepository.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc`
directly — the same public method `TicketDetailService` already uses for
`/full`'s `comments` array. One repository, two independent read paths; the
proxies share a persistence context and cost nothing extra.

A comment folds into the stream as a synthesised `COMMENTED` row with id
`100_000 + comment.id` — `frontend/src/mocks/handlers/tickets.ts`'s own
offset, kept identical on purpose. It is what lets a merged, cursor-paged
stream over two id spaces resume unambiguously: a bare `history.id` and
`comment.id` can collide, and a cursor naming only a number could not say
which table it meant.

`include=attachments` is declared on the contract and **not honoured**. C-060
is the attachments tab; nothing here pretends to interleave a source that
does not exist yet.

## ⚠ `entryHash` is deliberately not on the wire

The contract's `HistoryEntry.entryHash` is not in `TicketHistoryDtos`.
`TicketJournal.historyFor`'s own javadoc says plainly: "callers rendering
these to a client should drop the hashes — they are what A-044 verifies, and
publishing them tells an attacker the shape of what a forgery would have to
reproduce." That instruction is closer to the data than the contract text is,
so it wins here. The field is not in the contract's `required` list, so a
client that expects it degrades to "field absent" rather than a broken
response. **Needs Stream A sign-off** — either the contract drops the field or
this package is told the exposure is acceptable and grows a narrower
tamper-evidence signal than the raw chain hash.

## `stageCode` and `iterationNo` are always null

Neither exists on `ticket_history` — a handoff's stage lives on
`ticket_stage_transitions`, which C-042 has not built, and nothing pairs a
handoff's `ticket_history` row with its transition yet. `CommentDtos.CommentDto.iterationNo`
documents the identical absence, for the identical reason: writing an invented
value would be indistinguishable from a real first iteration, and worse than
leaving the field honestly empty.

## `String ticketId`, not `long`

Following `EffortLogController`'s and `PriorityChangeController`'s note on the
same question: the contract's `TicketId` is the `CRM-26-00347` code, and
`ScopedTickets.requireByCode` resolves it.

## Cursor pages in memory, over one per-ticket fetch

`journal.historyFor` and (when asked) `TicketCommentRepository`'s query both
return a ticket's full, bounded list — the same call `EffortLogService.list`
and `TicketDetailService` already make for their own per-ticket journals, on
the same reasoning: a ticket's own history is not the cross-ticket dashboard
scan CLAUDE.md's `COUNT(*)` rule targets. The two lists are merged, sorted by
`(createdAt, id)` and paged with `Cursor`/`CursorPage`/`PageLimit`, in Java.

## For other streams

⚠ **Stream A — `PermissionMatrix.java` gains one row** (`everyRole`, the read),
on `CommentController.list`'s and `EffortLogController.list`'s identical
argument.
