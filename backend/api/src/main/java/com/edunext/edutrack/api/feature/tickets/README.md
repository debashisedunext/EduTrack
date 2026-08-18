# feature/tickets

**Owner: Stream C · Divyansh**

Ticket CRUD, detail, cycles, reopen, comments, attachments, effort. Screens S-17…S-24.

## What is here

| Class | Task | What it does |
|---|---|---|
| `TicketCode`, `TicketCodeGenerator`, `TicketSequenceRepository` | C-011 | Ticket ID generation via `LAST_INSERT_ID(expr)` |
| `SlaResolution`, `PlannedCloseDateService` | C-012 | SLA resolution and the planned close date it produces |
| `PlannedCloseDateController`, `PlannedCloseDateDtos` | C-012 | `GET /api/v1/tickets/planned-close-date` |
| `TicketExceptionHandler` | C-012 | RFC 9457 problems for the ticket routes |
| `UnknownProjectException`, `UnknownLevelException` | C-011, C-012 | The two failures those routes can produce |
| `PriorityChangeController`, `PriorityChangeService`, `PriorityChangeDtos` | C-020 | `PATCH /api/v1/tickets/{ticketId}/priority` — §4B.1's level change |
| `LevelReasonRequiredException` | C-020 | §4B.1's reason, mandatory once the ticket is assigned |
| [`attachments/`](attachments/README.md) | C-025 | Attachment security — sniffing, EXIF stripping, AV scan, signed URLs. Its own README |
| [`effort/`](effort/README.md) | C-035 | Effort logging, append-only, auto-stamped with the ticket's current stage and iteration. Its own README |
| [`history/`](history/README.md) | C-059 | `GET /tickets/{ticketId}/history` — read only, cycle-grouped, optionally interleaved with comments. Its own README |

There is still **no ticket write service**. `POST /tickets` does not exist on the
server; the create form runs against D-004's mock. C-013's note records what the
save chain still needs and who owns each part.

## C-020 — the level change

### `original_level` is never written here, and that is the whole task

Not "is restored afterwards" — *never written*. There is no `setOriginalLevel`
call in `PriorityChangeService` and there must not be one. It is set once by the
create path and is the only column that can answer A-070's "how many were *born*
critical versus *became* critical". A single overwrite is unrecoverable: the
ticket then claims it was always Critical, the history row that would have
contradicted it agrees, and nothing in the system knows the difference.

This is the one route whose *purpose* is to move `level`, so it is the one place
the rule can be broken by an ordinary-looking line. `PriorityChangeServiceTest`
asserts it on three paths rather than leaving it to review.

### The clock start is the cycle's, not now — the opposite call from `ReopenService`

`ReopenService` recomputes from the reopen instant and argues that anything else
leaves the ticket "born breached". This method deliberately does the opposite,
and the two are consistent:

- a **reopen starts a new cycle**, so its clock genuinely begins at the reopen;
- a **level change happens inside a cycle whose clock is already running**, and
  an SLA is "resolve within N hours of being *reported*".

So escalating a three-day-old ticket to a four-hour level produces a planned
close date **in the past**, and the ticket is breached. That is the true answer —
it has been open three days and somebody has just decided it should have taken
four hours. A date measured from now would say it is comfortably on track, which
is the reading that lets a genuinely late Critical ticket sit quietly in a queue.
`levelChange.ts` on the frontend is the same function, so the preview the user
commits against cannot disagree with the row.

### The reason is a row rule, so it is not a Bean Validation annotation

§4B.1 makes the reason mandatory *once the ticket is assigned* — a condition on a
column the request names by id and does not carry. A `@NotBlank` on the DTO would
refuse the legitimate triage case, and a cross-field `@AssertTrue` cannot see the
ticket either. It lives in the service and is still reported as a **400 keyed
onto `reason`**, so S-20's dialog marks the textarea.

400 and not 422, which is the opposite call from `TicketNotClosedException` one
package over and is right for the opposite reason: there nothing the caller sent
was wrong and rewording could not help, here the request is missing a field the
caller can supply.

### ⚠ Found here — `{ticketId}` is a code, and two routes take a `long`

The contract's `TicketId` is a **ticket code**: `components/schemas/TicketId` is
`type: string` with the pattern `^[A-Z][A-Z0-9]{1,9}-\d{2}-\d{5,}$` and the
example `CRM-26-00347`, and `Ticket.ticketId` is that same schema. So every
client puts a code in that path segment — S-20's own URL is
`/tickets/CRM-26-00347` — and `ScopedTickets` has carried `byCode`/
`requireByCode` since A-035, described there as "the `CRM-26-00347` form users
actually type".

**`TicketDetailController.full` (A-052) and `ReopenController.reopen` (C-038)
both declare `@PathVariable long ticketId`.** Against the real backend that is a
400 for every request the frontend actually sends. It has not bitten because
both have only ever been exercised against D-004's mock, which routes on the
string, and no deviation is recorded for it in PLAN.md §4.

C-020 follows the contract instead — a route that only works when called wrongly
is not working — and `requireByCode` finally has a caller. **The other two are
raised, not fixed here:** one is Stream A's route and the other is another task's
test surface, and changing them quietly on a priority branch is how a 400 becomes
somebody else's afternoon.

### ⚠ The capability is borrowed

The route asserts `ticket.assign`. §4B.1 grants the level change to Admin, PM and
Support Desk — three roles, stated as plainly as §2 states its Reopen row — but
§2 has no "change priority" row, so no permission code exists for it and minting
one is a migration in Stream A's `db/migration/`. `PriorityChangeController`
carries the full argument for the borrowing and for why `ticket.reopen` and
`ticket.close` were the wrong things to reuse. It changes nobody's access today
and stops being safe the first time an administrator composes a custom role on
S-09.

## C-012 — the planned close date

### Two steps, deliberately separate

`resolve()` answers *how long*, in working hours. `preview()` answers *when that
lands*, and it does so entirely through `WorkingHoursService` (B-024). Nothing in
this package walks a calendar. That is the whole point: four private
implementations of "working hours" produce four different answers, and B-024's
own javadoc names this task as its caller.

### The resolution ladder

Most specific first, stopping at the first rung that answers. `SlaResolution.Source`
is carried onto the wire so a date is explicable rather than merely authoritative.

| Rung | Source | Where from |
|---|---|---|
| 1 | `PROJECT_TASK_TYPE` | `sla_policies (project, taskType, level)` |
| 2 | `PROJECT_LEVEL` | `sla_policies (project, null, level)` |
| 3 | `ORG_DEFAULT` | `sla_policies (null, null, level)` |
| 4 | `PRIORITY_DEFAULT` | `priorities.default_sla_hours` (S-12) |
| 5 | `TASK_TYPE_DEFAULT` | `task_types.default_sla_hours` (S-11) |
| — | `NONE` | nothing matched; the ticket has no planned close date |

**Rungs 4 and 5 go beyond §6's three, and that is a decision worth arguing with.**
`SlaPolicyRepository`'s javadoc stops at rung 3 with "missing all three means the
ticket has no SLA and the scanner leaves it alone" — the right answer for a
*scanner*, which must not invent a breach. It is the wrong answer for a *planned
close date*: a project whose SLA matrix has not been configured, which is every
project on its first day, would give every ticket `planned_close_date IS NULL`,
and that takes the ticket out of `SlaRepository`'s breach sweep, out of the
pre-breach warning, out of the delayed KPI and out of the Due Today saved view.
The ticket stops being tracked and nothing says so. Stream D's scanners key off
`planned_close_date` rather than off a policy row, so a date from a master
default escalates exactly like one from a policy.

**Rung 4 before rung 5** because rungs 1–3 are all keyed by level and the priority
master's default is the last figure that still varies with it. A task-type default
applied first would hand Critical and Low the same date, which is precisely the
behaviour §4B.1 wants the level dropdown to remove — and it would look like a
working feature, because a date still appears.

### Zero is not a target

A non-positive `resolutionHrs` is treated as no target rather than "due at the
instant it was raised". `addWorkingHours` returns `start` unchanged for it, so
honouring a zero would give the ticket a planned close date already in the past
and hand the scanner an immediate breach on something nobody has read.

### The two failures, and why they differ

`UnknownProjectException` is **404 and says nothing more** — once A-034's
`ScopeResolver` lands, an out-of-scope project arrives here as "does not exist",
and a response that distinguished the two would confirm which project ids are
real. `UnknownLevelException` is **400 and does echo the value**: a level code is
not a row id. Both exist because the alternative was to let a bad argument fall
off the end of the ladder and answer "no SLA", which on screen is
indistinguishable from a correctly spelled level nobody has configured — and the
two need opposite fixes.

## Open items

- ⚠ **Needs Stream D sign-off — `contracts/openapi.yaml`.** `previewPlannedCloseDate`,
  `PlannedCloseDatePreview`, `PlannedCloseDateResponse` and `SlaSource` were added
  in-session, the same precedent C-011 and C-015 set for a blocking additive
  contract edit with a flagged sign-off rather than a synchronous ask. Additive
  only; `check-conventions.py` clean at 106 operations.
- ⚠ **`SlaPolicyWrite.taskTypeId` is required**, so `GET /projects/{id}/sla-policies`
  cannot express the layered `(project, null, level)` and org-wide rows the table
  actually stores. S-13's editor will need either a nullable `taskTypeId` or a
  separate representation for the defaults; today the mock resolves each cell
  instead, which is right for reading and cannot round-trip a write.
- **Not verified by a run.** Only JDK 8 is installed on the machine this was
  written on, so nothing in `backend/` was compiled or tested locally — CI's
  Temurin 25 is the first thing to execute it.
