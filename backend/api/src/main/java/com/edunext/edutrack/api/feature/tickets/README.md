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

There is still **no ticket write service**. `POST /tickets` does not exist on the
server; the create form runs against D-004's mock. C-013's note records what the
save chain still needs and who owns each part.

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
