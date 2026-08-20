# feature/masters/modules

**B-064 · §7.3 Module Master · Stream B**

The eight product areas a concern can be raised against — Student, Admission,
Fees, Examination, Attendance, Library, Inventory, Parent App. The `Module`
field of §7.5's "Where it happened" group.

| Operation | Path | Who |
|---|---|---|
| `listModules` | `GET /api/v1/masters/modules` | all six roles |

That is the whole feature. One read.

**No migration.** C-065's `V20260819_1336__product_modules_and_where_it_happened.sql`
created `product_modules`, seeded the eight rows in §7.3's order, and added
`tickets.module_id` with a foreign key onto it. B-064 mounts a route over a
table that already exists — nothing here touches schema, so nothing here needs
Stream A's review.

## The seventh route that existed everywhere except the server

`listModules` has been in `contracts/openapi.yaml`, in the MSW mock and in the
generated TypeScript client since D-060, with no controller behind it — after
B-023's nine calendar operations, B-014's `PATCH /users/{userId}/status`,
B-018's two SLA operations, B-020's `listTaskTypes`, B-021's `listPriorities`
and B-025's six client operations.

This one had the most already built on top of it. Three shipped Stream C
screens call it:

- `CreateTicketPage`'s module picker (C-068)
- S-20's "Where it happened" group and its inline editor (C-069)
- S-17's module filter and grid column (C-070)

All three are green against the mock. Against a real backend all three would
have 404'd, and the failure would have been quiet: `moduleName()` returns
`undefined`, the cell renders an em dash, and the screen says "no module was
recorded" for every ticket in the product.

## Every row, retired ones included

There is no `includeInactive` parameter and no active-only query — not on the
route, and not on `ProductModuleRepository` either.

The route serves two callers who need the same response for opposite reasons:

- a **picker** offers only `isActive` rows, and filters client-side;
- a **grid** renders the name of whichever module a ticket was actually raised
  against, retired or not.

Filter server-side and the second caller gets a blank cell. That reads as
missing data rather than as a retirement, and nothing about it looks wrong — so
it ships. The mock has carried a retired `Transport` module referenced by one
seeded ticket since D-060 precisely so a fixture cannot hide the difference;
`ModuleMasterIT` asserts the same thing against real MySQL.

This matches `listTaskTypes` and departs from `listPriorities`. The departure
was B-021's and its reason was that its consumers *could not* filter —
`CreateTicketPage` maps priorities straight into `LevelPicker`. This master's
consumers already do: `whereItHappened.ts` keeps the name lookup and the
editor's offer list as two separate functions.

## No writes, and that is the task

Reference data. The client asked for a fixed list; the table exists so that
changing it later is a row rather than a release.

No `POST`, no `PATCH`, and therefore no `GET /modules/{moduleId}` — the sibling
masters carry a detail read only to emit the `ETag` their `PATCH` needs as
`If-Match` (CONVENTIONS.md §5). B-011 added one to serve a write, not as a
pattern to copy.

A Module Master admin screen, if it is ever wanted, is a **new task** on the
S-11/S-12 pattern — ETag, create, a `PATCH` that retires rather than deletes,
and a `ticketCount` on every row to make the retire decision informed. It is not
a widening of this one.

## The write-side rule lives in Stream C, and stays there

`feature/tickets/ModuleGuard` (C-067) refuses a deactivated module when a
ticket is written — 400, keyed on `moduleId`. That is the right home for it:
the rule is about the ticket write, not about the master read, and duplicating
it here would give the codebase two answers to one question.

It reaches `product_modules` through a raw `JdbcClient` rather than through
`ProductModuleRepository`. Noted rather than changed — `feature/tickets` is
Stream C's directory and the consolidation is Stream C's call.
