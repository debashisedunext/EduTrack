# Role & Permission Master — S-09 (B-015)

`GET /masters/permissions` · `GET|POST /masters/roles` ·
`GET|PATCH|DELETE /masters/roles/{roleId}` ·
`PUT /masters/roles/{roleId}/permissions`

The data layer was already here. A-003 created `roles`, `permissions` and
`role_permissions`; B-001 seeded six roles and eighteen capabilities from
blueprint §2, cell by cell; and `Role`, `Permission`, `RolePermission` and their
three repositories in `domain.identity` were written with this screen named in
their javadoc — `RolePermissionRepository.deleteById_RoleId` is documented as
"the matrix saves as replace-all". **B-015 adds no migration.** It is the rules
the schema deliberately does not encode, plus the HTTP surface.

## The matrix is `category × capability`, not `module × CRUD/approve`

Blueprint S-09 says *"a permission checkbox matrix (module ×
create/read/update/delete/approve)"*. The seeded vocabulary is not CRUD-shaped:
eighteen dotted capability strings — `ticket.handoff`, `ticket.force_move`,
`history.edit_delete` — in six categories. This screen renders **category ×
capability**, and the deviation is deliberate on two grounds.

**Those codes are load-bearing.** `AuthUserRepository` reads them into the JWT
`permissions[]` claim and A-033's `@PreAuthorize` expressions name them. Cutting
a new CRUD vocabulary to suit a layout would be a cross-stream breaking change
wearing a UI costume — and would need Stream A's sign-off on the token contract
to be worth anything.

**And the grid does not exist to be drawn.** The seven code prefixes against the
sixteen distinct verbs give a 112-cell grid with 18 cells filled. Sparse to the
point of unreadable is not a more faithful rendering of the blueprint's intent
than the sections it would replace. B-001's own seed header already describes
S-09 as rendering these codes.

## Four refusals, all of them service-layer

None of these are enforced by the schema, which is why they are here rather than
in a migration.

| Refusal | Status | `type` |
|---|---|---|
| Deleting a system role | 409 | `system-role-undeletable` |
| Deleting a role resources still hold | 409 | `role-in-use`, with `userCount` |
| Changing a role's `code` | 409 | `immutable-field` |
| Granting `history.edit_delete` | 422 | `ungrantable-permission` |

**The system check runs before the in-use check.** A system role always has
holders, so testing in-use first would report "reassign 6 people" for a role that
could never be deleted however many people were reassigned. (The mirror of
B-014's ordering lesson on the bulk-deactivate path, for the same reason.)

**`users.role_id` is a foreign key without a cascade.** Without the in-use check
the database refuses the delete anyway — as a constraint violation naming a MySQL
index rather than a way forward. The check is the good error message; the FK is
the thing that is actually true under a race.

**`code` is immutable for the `project_code` reason.** It is denormalised into
every issued JWT's `role` claim, into the `@PreAuthorize` expressions compiled at
startup and into `workflow_transitions.role_code`. Changing it orphans all three
at once while leaving each of them looking healthy. `RolePatch` carries a `code`
field *only so that sending one can be refused* — leaving it off the record means
Jackson discards it silently and a caller who believed they renamed the role is
told the save succeeded.

**A system role may be renamed and deactivated.** `isSystem` guards deletion,
not editing; blocking a deactivation would make a role impossible to retire from
the pickers without breaking the resources that hold it.

## `history.edit_delete`

Blueprint §2: *"Edit / delete history or ribbon — ❌ (nobody can)"*. B-001 seeds
it with zero grants **so that S-09 can render it**, disabled, rather than omit
the row and leave an admin unsure whether the guarantee exists or was forgotten.

`RoleService.UNGRANTABLE` is a constant, **not** a `permissions.is_grantable`
column. A column would be truthful and editable — one `UPDATE` from unlocking the
append-only rule CLAUDE.md calls "the guarantee that erodes first". The rule is
not "this row is currently ungrantable", it is "the append-only guarantee forbids
it", and that belongs in code that ships with the enforcement it describes.

The refusal is server-side and not merely a disabled checkbox: this `PUT` is the
one reachable UI edge on that guarantee.

## Replace-all, and why `flush()` is not decoration

`PUT .../permissions` clears the role's grants and inserts the new set. Delete is
legitimate here and nowhere else in this codebase — `role_permissions` is
*current state*, not the append-only audit.

The delete and the inserts hit the same rows inside one transaction. Without
`grants.flush()` between them, Hibernate can order an insert ahead of the delete
and collide with the composite primary key it is about to remove.
`RoleMasterIT.replaceAllRoundTrips` saves three times in a row — including "same
set plus one minus one", the shape a second save from the screen actually takes
— because that is the sequence a single-save test does not exercise.

## What this changed outside its own package

Two files, both flagged rather than done quietly:

- **`domain.identity.UserRepository`** gained `countByRoleId` — a one-line
  derived query. Stream A's package, but it is where every other consumer of
  `roles` already reads from, and the alternative is a second count somewhere
  else that can disagree with it.
- **`ResourceController`** dropped its hardcoded
  `Set.of("ADMIN", "PM", … )`. Its javadoc said *"B-015 owns the role master;
  when it lands, this reads from it"* — it now does. A role an Admin adds
  through S-09 filters the resource grid like any other; before, the first
  custom role would have 400'd there and the failure would have looked like a
  bug in the Role Master.

## Known limitation, flagged not fixed

**A custom role cannot yet be assigned to a resource.** `RoleCode` in
`contracts/openapi.yaml` is a closed six-value enum, typed onto `UserRef`,
`UserWriteRequest` and the `listUsers` filter. Opening it touches Streams A, C
and D and is not B-015's call. Until then a custom role is definable and
grantable but not selectable, and the create dialog says so rather than letting
an admin find out from an empty picker.

## Permissions

- **Reads** — `@PreAuthorize("isAuthenticated()")`, so all six roles. Role names
  render on every ticket card and resource row, and the capability catalogue is
  blueprint documentation; `GET /me` already returns the caller's own grants.
- **Writes** — `@PreAuthorize("hasAuthority('resource.manage')")`. Only Admin
  holds it; the other five get 403.

**The writes assert `resource.manage`, not `master.write`** — easy to confuse,
because this is a master screen served under `/masters`. B-001 seeds
`resource.manage` as *"Manage resources, **roles**, reporting manager"* and
`master.write` as task types, SLA, workflow and holidays. Blueprint §2 puts
roles with resources, so this follows the seed rather than the URL. The two are
indistinguishable today and would not be the moment somebody is given master
data without being given the roles that govern who can touch it.

Asserting the capability rather than `hasRole('ADMIN')` is A-033's rule, and
this controller is the reason it exists: **a hard-coded role check would keep
refusing a seventh role that this very screen had just granted the capability
to.**

A-036's parameterised every-role × every-route suite is still the statement of
record when it lands; until then the annotations above and this section are it.

**403 and not 404 on the writes** looks like a breach of CLAUDE.md's
no-existence-leak rule and is not: master data is not row-scoped. Every role is
already public through `listRoles`, so a 403 conceals nothing a 404 would have
hidden — the CONVENTIONS.md §7 carve-out for failures that do not depend on a
row. Recorded in `contracts/check-conventions.py`'s `ROWLESS_403` with that
reason, beside `/audit-logs`.

## Tests

- `RoleServiceTest` — 21 unit tests, the decisions against mocks.
- `RoleControllerTest` — 11 unit tests: the `If-Match` precondition, and the 404
  that comes before it (a 428 for a role that does not exist would send the
  caller to fetch a tag from a URL that will 404 as well).
- `RoleMasterIT` — 12 against real MySQL: that B-001's seed is shaped the way
  this screen assumes, that replace-all survives the composite key, and that the
  in-use refusal is doing work the foreign key would otherwise do badly.
- `MasterRoutesTest` — `RoleController` is in the `CONTROLLERS` list and pinned
  to `/api/v1/masters`.
