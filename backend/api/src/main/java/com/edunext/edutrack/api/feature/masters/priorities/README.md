# `masters/priorities` — the Priority / Level Master (S-12, B-021)

| File | What it is |
|---|---|
| `PriorityController` | Four operations at `/api/v1/masters/priorities`. No `DELETE`. |
| `PriorityDtos` | The wire types, and the Bean Validation that actually enforces the contract's `pattern`/`maxLength`. |
| `PriorityService` | The four rules the schema does not encode. |
| `PriorityUsageRepository` | The three usage counts, in plain SQL. |
| `PriorityExceptionHandler` | RFC 9457 problem documents, scoped to this controller. |

## Nothing served this table until B-021

`GET /masters/priorities` has been in the contract, in the MSW mock and in the
generated TypeScript client since D-001 with **no controller anywhere in the
backend** — the fifth instance of "declared, mocked, never mounted" after
B-023's nine calendar operations, B-014's `PATCH /users/{userId}/status`,
B-018's two SLA operations and B-020's `listTaskTypes`.

It is the second in a row where shipped screens already call it.
`CreateTicketPage` builds its `LevelPicker` from this route and
`TicketListPage` builds its level filter from it. Both would have 404'd on
first contact with a real backend. `MasterRoutesTest` pins the mount point.

## Nothing has a foreign key to this table, and that is deliberate

`tickets.level`, `task_types.default_level` and `sla_policies.level` are all
`VARCHAR(10)` columns holding the **code**. A-007's migration states the trade:
no referential integrity on `level`, in exchange for a master that can change
without rewriting history.

Two consequences run through every file here:

1. **There is no `DELETE`, and the absence matters more than it does for task
   types.** A task type is pointed at by three foreign keys, so a delete at
   least *fails* loudly. A delete here would **succeed**, and every ticket ever
   raised at that level would render a code nothing resolves. Retiring is
   `isActive: false`.
2. **Every usage count keys on the code, never on `priorities.id`.** A count
   written as a join on the id would compile, run, and return zero for every
   level — and no unit test with a mocked repository could tell. That is what
   `PriorityMasterIT` needs a real MySQL for.

## The four refusals

| Rule | Where | Status |
|---|---|---|
| `level` is immutable once created | `PriorityService.update` | 409 `immutable-field` |
| `level` and `name` are unique | `PriorityService.create`/`update` | 409 `duplicate` |
| Exactly one **active** level is the escalation target | `applyEscalationFlag` | 409 `escalation-target-required` |
| A level active task types default to cannot be retired | `guardRetire` | 409 `in-use` |

### The escalation flag is a pointer, not an attribute

Blueprint §1 and §6: a task crossing its Planned Close Date is auto-promoted
*to* Critical. A-007 wrote the column's meaning down as "the level the SLA
engine escalates TO on breach" — and then left it a bare
`TINYINT(1) NOT NULL DEFAULT 0` with no constraint and, until B-021, nothing in
the codebase reading or writing it at all.

Two silent failure modes, both discovered by Stream D's scanner rather than by
whoever caused them:

- **Zero flags** — auto-escalation has nowhere to promote a ticket to, and one
  of the product's three headline behaviours stops. Clearing the last one is
  refused.
- **Two flags** — the target is ambiguous and which one wins is whatever order
  the scanner's query happens to return. So setting the flag is
  **single-writer**: it clears every other row first.

An admin moves the target by setting it where they want it, never by clearing
it where they don't. The MSW mock had flagged **two** levels since D-001.

### Retiring: three consequences, one of which refuses

The asymmetry with B-020 — which never refuses a deactivate — is about what the
leftover state does, not about how much is referenced.

| Count | Blocks? | Why |
|---|---|---|
| `ticketCount` | No | Tickets keep the level and go on rendering it. That is the whole point of the `VARCHAR`. Refusing would leave an org unable to retire a level it has stopped using. |
| `slaPolicyCount` | No | `SlaMatrixService` builds its column axis from the active levels, so the column simply leaves every project's grid. Nothing is deleted; reactivating brings it back. |
| `taskTypeCount` | **Yes** | `TaskTypeService.normaliseLevel` refuses a retired level as a `defaultLevel`. A type left pointing here fails validation on its next save, on a screen whose admin cannot see what caused it. **One screen must not be able to put another into a state it cannot get out of.** |

## The ordering bug in `update`, and how it is closed

`{"isActive": false, "autoEscalates": true}` is a single request that passes
both guards if each reads the entity's *stored* state: the escalation check sees
the level still active, and the retire check sees it not yet flagged. The end
state is a retired escalation target — exactly what both guards exist to
prevent.

`update` derives `willBeActive` once, before anything is written, and passes it
into `applyEscalationFlag`. The alternative — ordering the two writes carefully
— is the kind of thing a later tidy-up reorders without noticing.
`PriorityServiceTest.retireAndFlagInOneRequestIsRefused` is the regression test,
and the MSW handler mirrors the same derivation.

## What this task does not deliver

**S-12 says "Admin can add further levels without a release", and a fifth level
is refused with a 400.**

`Level` is a closed four-value enum in the contract, and it types
`Ticket.level`, `Ticket.originalLevel`, `TaskType.defaultLevel`,
`SlaPolicyWrite.level`, `SlaPolicyCell.level`, `ChangeTicketPriorityBody.level`
and two query parameters. A fifth code stored here would serialise into a
response the generated TypeScript client's own zod schema rejects — a screen
that breaks on read because of what somebody saved on a different screen — and
Stream C's `LevelPicker` and `columns.tsx` key their chip variants off
`Record<Level, …>` maps a fifth key would leave `undefined`.

Opening it is a coordinated change across Streams A, C and D. The refusal names
what has to change rather than accepting the row and letting it be discovered as
a rendering failure. `TaskTypeService.normaliseLevel` already refused the mirror
case and named B-021 as the task that would decide; this is the decision.

**Every other field S-12 lists is fully editable** — name, colour, default SLA
hours, escalation flag, order, retire and reactivate.

## Permissions

Reads are open to all six roles: every role may raise a ticket (§2 row 3), a
ticket must carry a level, and the create form's picker is this route. Writes
are `master.write` — Admin alone.

§2's row reads "Master data (task types, SLA, workflow, holidays)" and does not
name priorities, so the capability is reasoned rather than read off, the way
B-018 had to reason the SLA tab's. The list is illustrative: S-11 and S-12 are
consecutive screens in the same Masters section of §7, a level's `defaultSlaHrs`
is rung 4 of the same §6 ladder that row's "SLA" refers to, and the alternative
B-018 weighed and discarded — `project.manage` — is about a project rather than
the organisation's vocabulary.

403 and not 404 on the writes, recorded in `check-conventions.py`'s
`ROWLESS_403`: master data is not row-scoped, and every active level is already
public through `listPriorities`.

## Tests

| Suite | What it holds |
|---|---|
| `PriorityServiceTest` | 30 unit tests — the four refusals, the list default, the ordering bug |
| `PriorityControllerTest` | 11 — `If-Match`, the `ETag`, the 404 ordering, and that no mapping is a `DELETE` |
| `PriorityBodyValidationTest` | 14 — that the annotations the contract describes actually run |
| `PriorityPatchTest` | 7 — absent vs. explicit null, through a real Jackson |
| `PriorityMasterIT` | 19 against real MySQL — the seed's shape, and that the counts read the columns they claim to |
