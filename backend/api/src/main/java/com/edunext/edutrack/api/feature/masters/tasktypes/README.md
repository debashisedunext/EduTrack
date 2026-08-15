# feature/masters/tasktypes

**B-020 · S-11 Task Type Master · Stream B**

The eleven types blueprint §S-11 lists — Change Request, Production Bug, Client
Request, Future Release, Internal Bug, Client Bug, Server Issue, Network Issue,
Browser Issue, Performance Issue, Other — with icon, colour, default level and
default SLA. An Admin may add more.

| Operation | Path | Who |
|---|---|---|
| `listTaskTypes` | `GET /api/v1/masters/task-types` | all six roles |
| `getTaskType` | `GET /api/v1/masters/task-types/{taskTypeId}` | all six roles |
| `createTaskType` | `POST /api/v1/masters/task-types` | Admin (`master.write`) |
| `updateTaskType` | `PATCH /api/v1/masters/task-types/{taskTypeId}` | Admin (`master.write`) |

**No migration.** A-007 created `task_types` and B-002 seeded it; every column
S-11 needs was already there. A `CHECK` on `default_level` was considered and
rejected — it would fix the four levels in the schema, which is exactly what
B-021 exists to unfix.

## There is no delete

`tickets.task_type_id`, `sla_policies.task_type_id` and B-019's
`project_task_types.task_type_id` are all foreign keys **without** cascades.
B-019's migration wrote the rule at the constraint before this screen existed:
*"an allow-list row referencing a task type is a reason that task type must not
vanish, and B-020's master deactivates rather than deletes for exactly this class
of reason."*

Retiring is `PATCH { "isActive": false }`. What it does, and what the screen says
it does:

- the type leaves the create form's picker (which already filters on `isActive`);
- it leaves **every project's SLA matrix**, because `SlaMatrixService` builds the
  grid from `activeTaskTypes()`;
- every ticket that carries it still renders its own name, because the list read
  returns retired rows too.

`ticketCount` is on every row rather than only in a confirmation, so the size of
that is visible before the click — the call B-015 made with `userCount` on a
role, and B-014 made with `openTicketCount` on the resource grid.

## The three refusals

| Refusal | Status | Enforced by |
|---|---|---|
| Duplicate `code` | `409` `duplicate`, keyed to `code` | this service **and** `uq_task_types_code` |
| Duplicate `name`, case-insensitively | `409` `duplicate`, keyed to `name` | this service alone |
| `code` changed after creation | `409` `immutable-field` | this service alone |

The name rule looks like tidiness and is not. `features/tickets/create/ticketForm.ts`
decides which types make the Client field mandatory (§4B.2) by **matching on the
display name**, so two types called "Client Bug" would take that rule with them —
and this screen is the only thing in the product that can create the collision.
Which is also why `code` is now on the contract's `TaskType` and is immutable:
it is the key a client should hold instead.

## `defaultLevel` is checked twice, and the order matters

1. **Against the `priorities` master**, and it must be active. That is the real
   referential rule, and it is a lookup rather than a constant because B-021's
   whole point is that an Admin can add a level — a hardcoded set is what B-015
   removed from `ResourceController`.
2. **Against `CONTRACT_LEVELS`**, the four values the contract's `Level` enum can
   carry. A fifth priority stored here would serialise into a response the
   generated client's own zod schema rejects: a screen that breaks on read
   because of what somebody saved on a different screen. Opening `Level` touches
   `tickets.level` in three other streams and is B-021's call.

The second refusal names B-021 in its message, so it reads as a limitation of
the wire format rather than as a fact about the organisation.

## `TaskTypePatch` is a POJO and every other DTO here is a record

B-017's lesson, applied where it bites again. `icon` and `defaultSlaHrs` are
genuinely clearable, so "absent" and "explicitly null" have to mean different
things — and Jackson fills an *absent* `Optional` creator property on a record
with `Optional.empty()`, the same value an explicit null produces. Both would
collapse into "clear it", and `PATCH {"name": "…"}` would silently wipe the
type's icon and its default SLA while echoing back a response that looked
correct. `TaskTypePatchTest` pins both directions.

The other five fields stay plain and nullable, where null unambiguously means
*leave alone*.
