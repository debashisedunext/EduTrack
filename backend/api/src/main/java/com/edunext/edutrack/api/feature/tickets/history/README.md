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

## ⚠ `entryHash` is declared and always null — not dropped

The first draft of this package left `entryHash` off `TicketHistoryDtos`
entirely, on `TicketJournal.historyFor`'s own javadoc: "callers rendering
these to a client should drop the hashes — they are what A-044 verifies, and
publishing them tells an attacker the shape of what a forgery would have to
reproduce." That is still the reasoning and it still wins on the *value*.

It cannot win on the *property*, though. `ContractConformanceTest` (D-005)
compares `HistoryEntry`'s declared property names against what springdoc
generates from this record, on every `GET`, and a name the server never
serves at all fails the build: `GET /tickets/{ticketId}/history — declared
but not served: [entryHash]`. That check has no way to tell a deliberate
security omission from the typo/rename drift it exists to catch — dropping
the field silently would have been exactly the kind of contract drift D-005
was built to stop, from the opposite direction.

So the field is declared and permanently null: `HistoryEntryDto.entryHash`
exists in the response as `"entryHash": null`, satisfying the shape check
while withholding the value. **Needs Stream A sign-off** — either the
contract drops the field, or this package is told the exposure is acceptable
and starts returning `row.getRowHash()`.

## `stageCode`, `iterationNo` and `actorRole` are null on a real row — always

Neither `stageCode` nor `iterationNo` exists on `ticket_history` — a handoff's
stage lives on `ticket_stage_transitions`, which C-042 has not paired with its
`ticket_history` row — and `actorRole` (C-032) has no equivalent at all: a
field change or a handoff carries no stamped role for its actor to fall back
to. All three stay null for every real row, for as long as that is true.

⚠ **C-034 found one place a blanket "always null" did not actually hold, and
C-032 found the rest of it.** `ticket_comments` stamps its own `stage_code`,
`iteration_no` and (since C-032) `author_role` at write time — a comment
written by a Developer during QA iteration 2 reads as exactly that forever,
even after the ribbon moves on and the author is promoted — and
`frontend/src/mocks/handlers/tickets.ts` has read `stage_code` onto the
interleaved row since C-059 shipped. `TicketHistoryDtos.HistoryEntryDto.ofComment`
now reads all three straight off the `TicketComment` row on a `COMMENTED` row;
a real `ticket_history` row is unaffected and still has nothing to read.
`iterationNo` and `actorRole` can still be null on a `COMMENTED` row: the
former when the comment precedes the ticket's first stage transition, the
latter when it predates the `author_role` column — neither is invented.

## `isClientVisible` — C-034, comment rows only

Declared on `HistoryEntry` and set from `!TicketComment.isInternal()` on a
`COMMENTED` row; null on every real `ticket_history` row, the same way
`stageCode` is. Blueprint §4B.5's "two backgrounds are now both drawn" rule
(`CommentCard`'s own) is what the interleaved stream was missing without it —
a reader of the History tab alone had no way to tell an internal comment from
a client-visible one, which is the one distinction §4B.5 treats as a leak
risk worth a dedicated visual signal rather than an extra click to find out.

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

⚠ **Stream D sign-off — `contracts/openapi.yaml`.** C-032 adds `HistoryEntry
.actorRole`, additive, mirroring `Comment.authorRole`. Client regenerated;
the diff is one property.
