# `masters/statuses` — the Status Master and its transition matrix (S-13 tab 1, B-039)

| File | What it is |
|---|---|
| `StatusController` | Six operations at `/api/v1/masters/statuses` and `/status-transitions`. No `DELETE`. |
| `StatusDtos` | The wire types, and the Bean Validation that actually enforces the contract's `pattern`/`maxLength`. |
| `StatusService` | The five rules the schema does not encode, and the retire cascade. |
| `StatusTransitionService` | The matrix: the whitelist's read, its replace, and the one invariant. |
| `StatusUsageRepository` | The two usage counts, in plain SQL. |
| `StatusExceptionHandler` | RFC 9457 problem documents, scoped to this controller. |

## Nothing served either table, and nothing even declared them

B-021 found the fifth instance of "declared, mocked, never mounted" — a route in
the contract and the generated client with no controller behind it. `statuses`
and `workflow_transitions` are the opposite failure and a quieter one: **two
seeded masters, eighty-two rows, no contract path, no mock, no client, no
screen.** Reachable only by a migration since 7 August.

So this task wrote the contract as well as the server, and no shipped screen
broke on first contact — there was no screen. `MasterRoutesTest` pins the mount
point so that stops being true silently.

## Blueprint §7.4 asked for a category and nothing could answer

S-13 tab 1 is *"status list, **categories (To-do / In progress / Done)**,
allowed-transition matrix per role"*. The column did not exist.

**It cannot be derived from `is_open` and `is_terminal`, which is the whole
reason it is a column.** `NEW` and `REOPENED` are To-do; `ON_HOLD`,
`AWAITING_INFO` and `REWORK` are In progress. All five carry
`is_open = 1, is_terminal = 0` — identical on both columns, three categories
apart. Deriving it would mean hard-coding the eight seeded codes in Java, which
puts the master's own vocabulary back in the application this screen exists to
take it out of.

`RESOLVED` is the mirror case and the one that keeps the column honest: `DONE`
work on a ticket that stays open until sign-off. **The category describes the
work; `isOpen` describes the ticket record.** A guard reading "DONE implies not
open" would refuse the row the blueprint asks for, so there is no such guard —
in the service, in the form, or in the mock.

## There is no delete, and here the stake is the highest of the three masters

`tickets.status` holds the *code* in a `VARCHAR`, deliberately not a foreign key.
So a delete would **succeed**, exactly as it would on `priorities`.

| Master | What a `DELETE` would do |
|---|---|
| Task types | **Fail** loudly — three foreign keys point at it |
| Priorities | **Succeed**, leaving old tickets rendering a level nothing resolves. Cosmetic |
| **Statuses** | **Succeed**, and strand every live ticket in that status with no move offered on any screen |

A level is decoration on a ticket that still works. A status is the left-hand
side of every transition lookup. Retiring is `isActive: false`, and even that
refuses while tickets are still there.

## The five refusals

| Rule | Where | Status |
|---|---|---|
| A ninth status code | `StatusService.normaliseCode` | 400 `validation` |
| `code` is immutable once created | `StatusService.update` | 409 `immutable-field` |
| `code` and `name` are unique | `StatusService.create`/`update` | 409 `duplicate` |
| Terminal and open at once | `guardTerminalAndOpen` | 409 `contradictory-state` |
| Retiring a status tickets are in | `guardRetire` | 409 `in-use` |

Plus three on the matrix — an unknown code, a self-transition, a duplicate cell —
and the one that is not like the others, below.

### The ninth status is refused, and the refusal is the honest answer

`StatusCode` is a closed eight-value enum in the contract and it types
`Ticket.status`, `TicketListItem.status` and two query parameters. A ninth code
stored here would serialise into a response the generated client's own zod
rejects — a ticket list that breaks on read because of what somebody saved on a
master screen — and Stream C's status chips key off `Record<StatusCode, …>` maps
a ninth key would leave `undefined`.

Identical in shape and reasoning to `PriorityService.normaliseLevel`, with one
difference worth noting: **S-12 promises "Admin can add further levels without a
release" and cannot deliver it. S-13 makes no such promise**, so here the closed
set is a constraint rather than an unkept one. The message still names what has
to change and who owns it.

### Retiring is not local, and that is the whole risk in this task

The gate Stream C consults is
`WorkflowTransitionRepository.existsByFromStatusAndToStatusAndRoleCodeAndIsActiveTrue`
— it reads the **transition** row's `is_active` and never looks at the status at
all.

So retiring `ON_HOLD` without touching the matrix leaves `IN_PROGRESS → ON_HOLD`
live: the master says the status is gone and the engine goes on moving tickets
into it. Nothing fails. The two simply disagree, and the disagreement surfaces on
a ticket page weeks later.

Closing it in Stream C's gate would have been the smaller diff and is not this
stream's file to change. Closing it here costs one extra write on a rare
operation:

- **Refused while any ticket is in the status.** Those tickets would be stranded
  — no transition out is offered, and the screen that could fix it is a
  different one. The same "one screen must not put another into a state it
  cannot get out of" rule that makes `taskTypeCount` block a level retire.
- Otherwise **every transition into and out of the status is deactivated in the
  same transaction**, and the count comes back as `deactivatedTransitions` so the
  dialog can state it before the click rather than after.

**Reactivating does not bring them back.** A restore would have to guess which
rows were deactivated *by this retire* versus cleared deliberately in between.
Guessing wrong grants a move nobody approved — on a whitelist, the one direction
an error must not go.

## The matrix

### G-3 is data, and this task deliberately keeps it that way

`workflow_transitions` is a whitelist: a missing `(from, to, role)` means the
move is impossible, and there is no second place to consult. Which is why
governance decision G-3 (PLAN.md §5) — *may a Developer close a ticket?* — is
expressed as the **absence** of a `(RESOLVED, CLOSED, DEVELOPER)` row.

B-003's seed header put it as "changing that policy is a seed edit, not a
deploy". S-13 makes it a screen edit, and `StatusTransitionService` **does not
hard-code G-3 as a refusal**. Writing that rule in here would put back into code
the one decision the table exists to keep out of it, and would mean an
organisation whose sign-off process differs from ours cannot express it without a
release. The S-13 grid flags those cells and lets an Admin change them anyway —
advice, which is not the same thing as a lock. `StatusTransitionServiceTest`
asserts that a Developer close is *accepted*.

### `PUT`, upsert, and the one invariant

`PUT` and not `PATCH`, because a cell's meaning depends on its neighbours:
`guardAtLeastOneOnCreate` cannot be checked against a single cell.

Upsert rather than delete-and-reinsert. A row already present keeps its `id` and
`createdAt`; a row absent from the body is **deactivated, not deleted**. That is
B-017's and B-018's argument against replacing `project_members` and
`sla_policies` by delete, applied to a table whose rows likewise carry facts —
`requiresReason` and `requiresEffort` are decisions somebody made, and a cleared
cell that kept them is one that can be restored as it was rather than re-guessed.
It is also why the read returns inactive rows: the grid has to render a cell an
Admin *cleared* differently from one nobody ever configured.

**At least one `fromStatus: null` row must survive.** With none, no role can
raise a ticket on any screen, and the screen that could undo it is this one. It
is the only edit here that can lock the product out of itself, so it is the only
one refused unconditionally — in the browser so the button can explain itself,
and again on the server because a browser is not a guarantee.

### An unknown code is refused, and B-008 is why

Neither `role_code` nor the two status columns has a foreign key, so a wrong code
is not a constraint violation — it is a row that silently matches no caller,
ever. That is exactly the defect B-008 found: thirteen seeded rows carrying
`SUPPORT_DESK` against a `roles` table holding `SUPPORT`, leaving the Support
Desk unable to make any status move at all, with nothing failing anywhere. The
database will not catch this; `StatusTransitionService.replace` is what does.

## The matrix carries its own ETag, and it is the only collection that does

Every other collection in the contract hands its precondition to a single-row
route, because the collection is a view over rows edited one at a time. There is
no single-row route here — there is no per-cell verb — so `listStatusTransitions`
emits the tag itself.

Without it, `replaceStatusTransitions` would have had to be exempted from
`If-Match` in `check-conventions.py`, and a whole-matrix replace is exactly the
write where a lost update is worst: the loser's cells vanish with nothing to
indicate they were ever there. The tag covers the **whole** matrix even on a
role-filtered read, or two Admins editing different columns would each save over
the other with both preconditions passing.

## Permissions

Both status reads and **the matrix read** are open to all six roles. The first
two on §2's argument: every role may raise a ticket (§2 row 3), every ticket
carries a status, and a role that could not list statuses could not render its
own ticket's chip.

The matrix read is the row worth arguing rather than copying. It looks like
policy configuration, which reads Admin-only — but it is also the list of moves a
ticket detail page offers, and Stream C's ribbon and status control have to know
which buttons to render. Restricting it would not conceal the policy either: a
user discovers a forbidden move by pressing it and being refused. **Authoring the
policy is Admin-only; seeing it is not.**

Writes are `master.write` — and unlike B-021's priorities this needs no argument
at all. §2's row reads "Master data (task types, SLA, **workflow**, holidays)",
S-13 is titled "Status, Stage & Workflow Template Master", and the transition
matrix is the workflow policy itself.

403 and not 404 on the writes, recorded in `check-conventions.py`'s
`ROWLESS_403`: master data is not row-scoped, and every active status is already
public through `listStatuses`.

## One finding recorded rather than fixed

**MySQL answers a violated `CHECK` with error 3819 and a missing `NOT NULL`
default with 1364, and Spring's MySQL error-code map contains neither.** Both
arrive as `UncategorizedSQLException`, which is *not* a
`DataIntegrityViolationException` — so any service that catches the latter to
turn a constraint breach into a 409 will not catch these two.

Nothing in B-039 depends on it: both rules are enforced in the service before the
database is reached, and the database is the second line. But it is worth knowing
before somebody writes that catch and watches a 500 go out instead. Adding vendor
codes to the translator is an application-wide change and Stream A's.
`StatusMasterIT.categoryCheckIsEnforced` carries the note.

## Tests

| Suite | What it holds |
|---|---|
| `StatusServiceTest` | 35 unit tests — the five refusals, the retire cascade, the end-state ordering |
| `StatusTransitionServiceTest` | The whitelist's refusals, the upsert, and that G-3 is *not* enforced |
| `StatusControllerTest` | Both `If-Match` preconditions, both `ETag`s, the 404 ordering, and that no mapping is a `DELETE` |
| `StatusBodyValidationTest` | That the annotations the contract describes actually run, nested cells included |
| `StatusMasterIT` | 20 against real MySQL — the backfill, the `CHECK`, and that the counts read the columns they claim to |
