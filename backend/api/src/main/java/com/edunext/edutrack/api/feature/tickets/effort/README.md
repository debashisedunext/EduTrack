# Effort — blueprint §4A.4

Append-only effort logging, auto-stamped with the ticket's current stage,
iteration and cycle. C-036 (Quick Update) and C-061 (the Effort tab) are its
consumers; this task is the write path and the paged read they call. C-041
adds the materialised totals both writes now keep current.

## What is here

| File | |
|---|---|
| `EffortLogController` | `POST /tickets/{ticketId}/effort`, `GET /tickets/{ticketId}/effort-logs` |
| `EffortLogService` | the stamp, the correction guard, the paged read |
| `EffortLogUserRefs` | `user_id`s → `UserRef`, one query per listing — a near-twin of `CommentUserRefs` |
| `EffortLogDtos` | the contract's `EffortLog`, field for field |
| `EffortCorrectionTargetRequiredException` / `EffortLogNotFoundException` / `EffortLogExceptionHandler` | the 400 and the 404 |

No migration. `ticket_effort_logs` and its `stage_code`/`iteration_no`/
`is_correction`/`corrects_entry_id` columns have existed since the A-006
baseline, and A-040's `TicketJournal` (`append`, `reverseEffort`, `effortFor`)
already carries the hash chain, the per-ticket lock and the correction
arithmetic — this task is the `api` layer in front of it, the same shape
C-020's `PriorityChangeService` is in front of `journal.append(TicketHistory)`.

## Two things worth knowing before changing anything here

**1. The stamp is the whole task, and it is read from the ticket, never from
the request.** `EffortLogService.append` sets `cycleNo`, `stageCode` and
`iterationNo` from `ticket.getCurrentCycleNo()` / `getCurrentStage()` /
`getCurrentIteration()` — the contract's own words for `logEffort` are "never
sent by the client, which would let a stale tab attribute effort to the wrong
stage", and a browser tab left open across a handoff is exactly the ordinary
case that sentence is about. `EffortLogServiceTest.OrdinaryEntry` pins it by
setting the ticket to one position and the caller to nothing about it at all.

**2. A correction does not use the caller's `hours` or `workDate`, even though
the schema requires both.** `TicketJournal.reverseEffort` computes the exact
negation of the entry it reverses and copies its work date — its own javadoc
says "the hours are not a parameter, and that is the point": a caller able to
name an arbitrary figure for a reversal could leave a residue in a cell that
reads as real work. `EffortLogService.correct` therefore never reads
`request.hours()` or `request.workDate()`; only `correctsEntryId` and `note`
make the trip. This is the same shape of deviation `PriorityChangeDtos`
documents for `reason` — a field the schema states as present and the service
does not always consult.

## The guard `TicketJournal` does not carry

`reverseEffort(effortLogId, note)` trusts the id it is given — the reversal it
builds copies `ticketId` **from the original**, so nothing in the journal
checks that the original belongs to the ticket named in this request's URL.
Left unchecked, `correctsEntryId` would be a way to reverse an entry on any
ticket in the system from any other ticket's route. `EffortLogService.requireOwnTicket`
closes it, done here rather than in the journal because `requireReversesItsOwnBucket`
only compares the correction *about to be built* against the original — always
true by construction once `ticketId` has already been copied from it.
`EffortLogServiceTest.Correction.refusesACrossTicketTarget` pins the 404.

## `String ticketId`, not `long`

Following `PriorityChangeController`'s note on the route beside this one: the
contract's `TicketId` parameter is the `CRM-26-00347` code, and `ScopedTickets.requireByCode`
resolves it. Two other ticket routes still declare `long ticketId` and work
only against the mock; that is raised there, not repeated here.

## `GET /effort-logs` pages in memory over `TicketJournal.effortFor`

Rather than a second Spring Data repository. `TicketDetailService` already
made the same call for the same table — a ticket's own effort log is a
bounded, per-ticket list, not the cross-ticket dashboard scan CLAUDE.md's
`COUNT(*)` rule is aimed at — and `CommentRows`' own javadoc explains why a
second read path would live beside this feature rather than as a method on
`TicketEffortLogRepository`: that interface is `domain`'s (B-005 / A-040), and
CLAUDE.md asks for that owner's sign-off rather than a quiet edit. Filtering by
`cycle`/`stage`/`iteration` and paging by cursor both run over the one fetch,
in Java.

**`cycleTotalHrs` and `grandTotalHrs` are computed from the unfiltered set**,
never from whatever page or filter the caller asked for — the headline figures
for whoever is working the ticket now, unaffected by which slice of the log
they happen to be looking at. `EffortLogServiceTest.Listing` pins this against
a stage filter that would otherwise change the total.

⚠ **One place this deliberately does not match `frontend/src/mocks/handlers/tickets.ts`.**
The mock computes `cycleTotalHrs` from the *already-filtered* row set rather
than from the ticket's unfiltered log, so a caller combining `?cycle=` with a
`stage=` filter that excludes the ticket's current cycle gets a mock total of
zero against a real total that still reports the current cycle's true figure.
Not fixed there — `frontend/src/mocks/` is Stream D's — but worth knowing before
trusting a discrepancy found only under `npm run dev`.

## C-041 · the materialised totals

`ticket_cycles.effort_hrs` and `tickets.total_effort_hrs` have existed since
the A-006 baseline, and `ReopenService`/`CloseService` already read them —
`ReopenService`'s own javadoc says "cycle N's effort is never touched" on the
assumption that `total_effort_hrs` is "already Σ across all cycles". Before
this task neither claim was true: nothing had ever written either column
outside `TicketFixtureGenerator`'s hand-computed figure, so every real `POST
/tickets/{ticketId}/effort` left both at zero. `EffortLogService.refreshTotals`
closes that — called from both `append` and `correct`, since a reversal is
itself a signed row through the same append and belongs in the same running
total.

**The cycle credited is the entry's own (`saved.getCycleNo()`), never the
ticket's current one.** A correction can reverse an entry on an earlier,
already-sealed cycle — reopening does not freeze a cycle against correction,
only against new ordinary entries — and crediting today's cycle instead would
land the adjustment in the wrong cell, the same class of mistake
`TicketJournal.requireReversesItsOwnBucket` guards against one layer down.
`total_effort_hrs` has no such split: every entry moves it the same way
regardless of cycle.

**No explicit `save()` on either write.** Both `ticket` and the freshly loaded
`TicketCycle` are entities already managed by this transaction's persistence
context, and both writes land under the per-ticket lock `TicketJournal.append`
took inside the same `@Transactional` method (PLAN.md §3.7) — the identical
dirty-checking pattern `ReopenService` and `CloseService` already use for
their own `Ticket`/`TicketCycle` mutations.

## Deliberately absent

- **Idempotency-Key replay.** Accepted per CONVENTIONS.md §4, following
  `ResourceController` and `ReopenController`'s identical note: the 24-hour
  replay store is A-035 and does not exist. Unlike a create guarded by a
  unique constraint, a retried effort log has nothing to catch it — a
  double-submitted `POST` really does double the hours. A bespoke store for one
  route is a bigger change than this task, and a shared one is Stream A's to
  design, as both precedents say of their own routes.
- **The backdating window and manager approval.** `EffortLogRequest.workDate`'s
  own description says "backdating is allowed within the configured window
  (default 7 days); beyond that it needs manager approval", but blueprint's own
  open-questions list (§ "Backdating of effort logs") still carries this as an
  unresolved recommendation, not a decided rule, and no config property or
  approval flow exists anywhere in the codebase to hang it off. Building one
  quietly here would be inventing the policy this task was never asked to
  settle. Raised rather than made.

## For other streams

⚠ **Stream A — `PermissionMatrix.java` gains two rows**, as the Definition of
Done requires — both `everyRole`, since `ticket.update_progress` is already
seeded for all six roles and the read needs no capability beyond
`isAuthenticated()`, on `CommentController.list`'s identical argument.
