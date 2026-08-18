# Bulk reassignment — blueprint §7.5 S-17 / S-24

`POST /tickets/bulk-reassign`, C-063. C-017 built the frontend against this
route before it existed on the backend — `ResourceService.REASSIGN_URL`'s
javadoc names the gap explicitly — and B-014 sends a blocked deactivation here
too. This task is the one implementation both callers share.

## What is here

| File | |
|---|---|
| `BulkReassignController` | the route, `hasAnyRole('ADMIN','PM')` |
| `BulkReassignService` | per-ticket transaction, the reassignment, the `REASSIGNED` history row |
| `BulkReassignDtos` | the contract's request and `BulkResultResponse`, field for field |
| `UnknownUserException` / `BulkReassignExceptionHandler` | the 400 for a `toUserId` that names nobody |

## No `fromUserId` anywhere in this package

Not an oversight — the contract never asks for one. S-17's grid assembles
`ticketIds` from a tick-box selection; S-24's wizard assembles the same field
from "every open ticket assigned to this resource". The two callers differ
only in how that list was built, and the server does not need to know which
one it was talking to.

## One transaction per ticket, not one for the whole request

`TicketJournal.append` is `Propagation.MANDATORY` and takes a per-ticket lock.
Five hundred appends inside a single request-long transaction would hold five
hundred locks at once, and a single row's failure would roll back all of them
— exactly wrong for a route whose own contract text promises "partial success
is possible and reported", the same guarantee `bulk-close`'s description
states in almost the same words. `BulkReassignService` opens a
`TransactionTemplate` per ticket instead, `PROPAGATION_REQUIRES_NEW`, the
pattern `AttachmentScanTask`/`ThumbnailTask` already set for the reason their
own comments give: a private method called from inside this class's own loop
never runs through the Spring proxy, so `@Transactional` on it would silently
do nothing.

## `hasAnyRole('ADMIN','PM')`, not a borrowed capability

Every other route in this feature asserts a §2 capability rather than a role
pair — `PriorityChangeController`'s javadoc argues why at length. This route
breaks that pattern deliberately: the contract spells out Admin-and-PM as an
explicit matrix rather than naming a capability, and no seeded code has that
exact grant set. `ticket.assign` is the closest by meaning and includes
Support, one role too many; `project.manage` matches the role pair exactly but
is a project-master permission borrowed to gate a ticket action, which is the
wrong kind of surprise for whoever reads `V20260806_0900__seed_roles_permissions.sql`
next to this annotation. Asserting the role pair the contract actually states,
and saying so in the controller, is more honest than picking the
less-wrong mismatch quietly. **Raised for Stream A**: a dedicated
`ticket.bulk_reassign` capability the day S-09 needs one.

`RouteAuthorizationTest.everyRoleLiteralNamesARealRole` validates the literal
against the six §2 role codes; nothing else in this codebase reads a `hasAnyRole`
annotation for meaning, so there is no drift to protect against beyond that.

## Row scope, not a `fromUserId` check

*Which* tickets a caller may reassign is `ScopedTickets.byCode`, exactly as
every other ticket route in this stream applies it — a PM reaching past their
own projects gets a per-ticket `"Not found or out of scope"` in the result,
never a 403 (A-035, no existence leak). There is no rule anywhere in this
package that a ticket must currently belong to any particular resource before
it can be reassigned to another; "reassign whoever holds it" is the entire
operation.

## Deliberately absent

- **Idempotency-Key replay.** Accepted, following `QuickUpdateController`'s
  and `ReopenController`'s identical note: the 24-hour replay store is A-035
  and does not exist yet.
- **Effort-attribution splitting.** The contract's `assignTicket` route
  (`POST /tickets/{ticketId}/assign`) writes `STAGE_REASSIGNED` and splits
  effort so both resources appear in the journey roll-up — that route does not
  exist in this codebase yet either, and this one does not borrow its
  behaviour. A bulk reassignment is a change of `assignedTo`, one `REASSIGNED`
  history row, nothing more; splitting effort attribution across a batch of up
  to 500 tickets is a different task's design, not a quiet addition to this
  one's scope.

## For other streams

⚠ **Stream A** — `permissions` gains a `ticket.bulk_reassign` row the day it is
worth minting; see the capability note above. No migration is added by this
task.
