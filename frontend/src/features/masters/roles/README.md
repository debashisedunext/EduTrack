# S-09 Role & Permission Master — B-015

`/masters/roles` (the grid) · `/masters/roles/:roleId` (the matrix, one role at
a time).

| File | What it is |
|---|---|
| `RoleListPage.tsx` | The grid, plus create and delete |
| `RolePermissionsPage.tsx` | Identity fields and the matrix for one role |
| `permissionMatrix.ts` | Grouping, toggling and dirty-checking — pure, no React |
| `roleQueries.ts` | The data layer |

## The matrix is `category × capability`

Blueprint S-09 says `module × create/read/update/delete/approve`. The seeded
vocabulary is eighteen dotted capability codes in six categories, already named
by the JWT `permissions[]` claim and by A-033's `@PreAuthorize` — so it is
rendered as it is, grouped by category. The backend README has the full
argument; the short version is that recutting a load-bearing vocabulary for a
layout is a cross-stream breaking change, and the CRUD grid would be 112 cells
with 18 filled anyway.

## Three things the screen does that are not obvious

**`history.edit_delete` is rendered, disabled, with its reason.** Blueprint §2 —
nobody may edit ticket history or the ribbon. A row that is simply absent reads
as a permission somebody forgot; a disabled one puts the append-only guarantee on
the screen you would go looking for it on. `toggle()` refuses it, the checkbox is
disabled, and the server answers 422 — three places, which is right for the one
rule that guards the audit trail.

**`groupState` excludes ungrantable rows from its count.** Counting
`history.edit_delete` would leave the History section's header checkbox
permanently indeterminate, because it can never be ticked, and a control that can
never reach "all" is a control nobody trusts.

**`userCount` is on every row of the grid, not only in the delete refusal.** A
delete that is going to be refused should be visibly going to be refused before
it is clicked. Discovering the count only in the error is how an admin ends up
clicking it four times to see whether anything changed.

## Two saves, one `ETag`

The identity fields go through `PATCH /masters/roles/{id}`; the matrix goes
through `PUT /masters/roles/{id}/permissions` as a replace-all. Both send the
same `If-Match`, and the tag covers `permissionCodes` — so a colleague's matrix
save invalidates a rename in progress. That is correct: they are two edits to the
same screen, and the alternative is one silently overwriting the other.

`useRole` caches the tag **alongside** the data, as `calendarQueries` does for
the working week. Fetching it at submit time would read a value the user never
saw, which defeats the guard entirely — the point is to detect that the role
changed between the read they edited and the write they sent.

`indeterminate` is a DOM property with no HTML attribute, so React cannot set it
declaratively; `MatrixGroup` sets it through a ref. Without that, a
partially-ticked section renders as a plain unticked box and reads as "nothing
granted here".

## The mock's `/me` permissions were wrong, and are now right

`mocks/handlers/rest.ts` held a hardcoded map of `ticket.read`, `ticket.write`,
`report.read`, `audit.read` — **none of which are among the eighteen codes B-001
seeds** or the ones `@PreAuthorize` names. Nothing read them, so nothing broke.
But once S-09 renders the real matrix, `/me` claiming a role holds `ticket.write`
while the Role Master shows `ticket.update_progress` is two answers to one
question in the same session. `permissionsOf()` now reads `db.roleGrants`, the
same store the matrix edits.

## Known limitation

A custom role can be created and given permissions, but **cannot yet be assigned
to a resource**: `RoleCode` in the contract is a closed six-value enum typed onto
`UserRef` and the S-08 form. Opening it touches three other streams. The create
dialog says so, rather than letting an admin find out from an empty picker.
