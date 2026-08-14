# Project Master — B-016 · S-10 · Team tab B-017 · SLA tab B-018

`/masters/projects` · `/masters/projects/new` · `/masters/projects/:projectId/edit`
· `/masters/projects/:projectId/team` · `/masters/projects/:projectId/sla`

The list, the create/edit form (B-016), the Team tab (B-017) and the SLA tab
(B-018). S-10 describes four tabs and three exist: **Settings is B-019**, with its
own contract path and its own task. It is not stubbed — a greyed-out tab and a
broken one look identical to a user, and an unbuilt screen rendered as a tab
makes the feature look finished.

Each tab is a **sibling route, not a nested one**. A layout route would put a
shared parent fetch above them, and they do not want one: the General tab reads
the project *with its `ETag`* because its `PATCH` requires it, and the SLA tab
needs its own `ETag` over a different resource entirely. One parent read could
not have served both.

---

## The SLA tab (B-018)

`SlaMatrixPage.tsx` at `/masters/projects/:projectId/sla`. Task type × level,
which is forty-four editable cells behind one wholesale `PUT`.

### Inherited figures are shown, and saying where they came from is the screen

`sla_policies` is layered, so every cell has a figure a ticket would really be
measured against — from this project, from a project-level default, from the
org-wide one, or from a master. A grid showing only this project's rows would be
almost entirely blank for a project that works perfectly well.

So each row shows what it resolves to **and where that came from**: an `override`
chip when this project set it, plain text otherwise. Only the override and
`NONE` are chipped — four coloured chips for four rungs would turn the one
distinction this screen exists to make into a palette, and `NONE` earns one
because it is not an inheritance at all: nothing in the product has a figure, so
tickets raised there get no planned close date and drop out of the breach sweep.

### `buildOverrides` is the function the feature turns on

The `PUT` body is this project's **overrides**, never the resolved grid. A cell
sent becomes a row for this project; a cell left out goes back to inheriting.
Sending the grid back — the obvious implementation, since the grid is what the
screen holds — would materialise every inherited figure as a project row and the
project would silently stop following the default it was displayed as following.
Nothing would look wrong until somebody changed that default months later and
this project did not move.

So a cell goes on the wire when it is **already an override** or when the user
has **changed it**, and never merely because it has a figure in it. Dirtiness is
compared as parsed numbers, so a cell somebody clicked into and out of — `16`
against `16.0` — is not a change.

### A Save button, where the Team tab has none

B-017 is right to save on change: each edit there is one `PATCH` of one field.
This operation is a **replace**, so save-on-change would mean forty-four
wholesale replaces racing each other while somebody types. One button, one
`If-Match`, one transaction.

The consequence is a dirty state, and what has to be right with it is what a
*cleared* box means: emptying a resolution target removes the override and the
cell inherits again. There is deliberately **no separate delete control** —
"no target here" and "inherit" are the same request as far as the row is
concerned, and two controls for one outcome is how they end up disagreeing.

### The form remounts on the `ETag`

`key={data.etag}` on `MatrixForm`. After a save — or after another tab's edit
invalidates the query — the drafts would otherwise keep the values the user was
editing while being compared against fresh `isOverride` flags, which is how a
cell somebody cleared reappears as an override. It is also what makes the 412
banner's "reload and reapply" true rather than advice.

### Validation is on the row, and the server is still the authority

The same four checks the service makes, run client-side, so a typo is answered
on the row that caused it rather than as a banner naming one cell out of
forty-four after a round trip. A `400` still lands in the banner: this exists to
save a round trip, not to replace the guard.

### What is deliberately not here

- **Editing the project-level default** (§6's rung 2, one row covering every
  task type at a level). A task type × level grid has no cell for "any task
  type"; it is rendered as the *source* of the cells it answers and left alone.
- **A read-only mode for non-Admins.** The write is `master.write`, which only
  Admin holds, and the frontend has no capability gate to hang this on —
  `/me` carries `permissions[]` and nothing reads it for gating. A PM sees the
  inputs and meets a `403` in the banner. **Flagged for Stream A**: this is the
  first masters screen whose read and write have different roles, so it is the
  first place the gap is visible rather than theoretical. Hardcoding a role
  check here is what B-015 removed from `ResourceController` and is not the fix.

---

## The Team tab (B-017)

`ProjectTeamPage.tsx` at `/masters/projects/:projectId/team`. Resources,
per-project role and allocation %, in one grid.

### Every cell saves itself

There is no Save button and no dirty state — each change is one `PATCH` of one
field. That is what makes the operation's absent `If-Match` safe: two people
editing different members, or different fields of one member, both land. A
form-shaped Team tab would have to send the whole roster on every save, and then
a stale tab really could undo somebody's change.

### Clear and omit are different, and the helpers are where that is decided

The `PATCH` reads an omitted key as "leave it alone" and an explicit null as
"clear it". `roleChangePatch` and `allocationChangePatch` in `projectTeam.ts` are
the one place that turns a UI change into one or the other, and they are tested
directly — **nothing in a rendered page would show the bug if they got it wrong.**
A patch that dropped the key instead of sending null would make "same as their
global role" and "not stated" write-once, and the row would go on displaying the
old value, which looks like a stale cache.

`allocationChangePatch` returns `null` for out-of-range input, which means "do
not send" — deliberately *not* the same as `{ allocationPct: null }`, which means
clear.

### An unstated allocation is blank, and it is not 100

Every membership written before this screen has no allocation, because nothing
had an input for one. `summariseAllocation` counts only the stated ones and
reports the rest separately; folding them in at 100 would have made almost every
real project read as wildly over-committed on the day this shipped.

**Zero renders as zero, not as blank.** "No capacity committed" is a decision and
"not stated" is an absence.

### The total shown is the project's, and it is not a warning

A team summing to 340% is normal — six people at varying commitments. The figure
that *would* be a warning is a **resource's** total across their projects, and
this screen has one project's rows; answering it would mean reading every other
project's team. **Flagged for B-061's capacity report** rather than approximated
from here.

### The removal refusal is shown before the click

`openTicketCount` is on the roster, so a member holding open work has their remove
button disabled with the count in its accessible name. B-014's lesson from the
resource grid: a refusal arriving *after* the action reads as a failure of the
click rather than as a fact about the organisation. The server's guard stays the
authority — a count that goes stale while the tab is open comes back as a 409 and
lands in the banner.

### The picker is not behind a button

An "Add resource" button that revealed a dropdown was two clicks to add one
person, and the second one only opened a control that is itself a disclosure. It
offers active resources who are not already on the team: a deactivated one is
refused server-side by name, and somebody already on it is a 409 — both would be
offering a choice whose only outcome is an error.

### "Same as global role" is a sentinel, and deliberately not a `ProjectRoleCode`

Radix refuses `value=""` on a `SelectItem`, so the absence needs a name.
`NO_PROJECT_ROLE` is `INHERIT`, which is not in the enum — so a bug that leaked it
onto the wire fails the server's `@Pattern` loudly instead of storing a seventh
role nobody defined. Same sentinel and same reason as
`ProjectAssignmentsEditor` (B-011), which is this control's mirror image on the
resource form.

### The tabs are two sibling routes, not a layout route

`ProjectTabs` renders the header for both. Nesting them under a layout route
would have made Team inherit General's read — which fetches the `ETag` its
`PATCH` requires and this screen never sends, so the shared fetch would be
caching a precondition nobody uses. Two routes, one header, one cheap query each,
and react-query dedupes the overlap.

The create form gets no tab strip: the Team tab writes `project_members` rows,
which need a project id that does not exist until the form is submitted.

### Invalidation reaches the resource screens

A membership written here is the same `project_members` row S-08's Projects
section reads and the S-07 grid prints a project column from. `projectTeamQueries`
invalidates `['/users']` as well as the roster — the two screens edit one table
from two sides, and the cache has to know that.

---

## The Project Master (B-016)

## `status` has three values and `isActive` is derived

`status` is Active / On Hold / Closed. `isActive` is the boolean five other
screens have always filtered on, and it means **`status !== 'CLOSED'`**.

Deriving it from `status === 'ACTIVE'` would mean that putting a project on hold
silently removed it from the create-ticket picker with nothing on the form saying
why. Whether On Hold *should* stop new tickets is a real question; it belongs on
Stream C's create form where it can carry a message, not in a boolean.

**The grid's status filter defaults to All, including closed.** A master screen
whose purpose includes reopening things must not hide the things to reopen — the
same default the resource grid uses.

## The code field disables itself, and says how many

`ticketsIssued > 0` closes it, which is the same test the server applies, read
off the same field on the same response.

Disabled rather than hidden, with the count in the hint. A missing input reads as
a rendering bug, and "347 ticket IDs already carry this prefix" is the kind of
thing an admin should be able to see rather than discover from a 409 after
typing a replacement.

`isCodeEditable` in `projectForm.ts` is the one statement of that rule on this
side, and `projectForm.test.ts` pins that it is the *issued count* and not the
project's age or its ticket rows.

## The grid caps at 200 and says so

The server pages by a keyset cursor over `(name, id)`; **this grid asks for 200
in one request and does not follow `meta.nextCursor`.** That is a real
limitation, and the page states it when `hasMore` is true rather than leaving a
truncated list looking complete — a silent cap reads as "these are all the
projects" when it is not.

Wiring the cursor up is a small change here and needs nothing on the API side.
It is left until an organisation has enough projects to want it, because
infinite scroll on a list that is usually a dozen rows is machinery with no
reader.

## Colour is a palette, not a hex input

CLAUDE.md: never introduce a colour that is not a token. The picker offers the
eight `--chart-*` swatches from blueprint §12.1 as a radio group — labelled, so
the choice reaches a screen reader as "Indigo" rather than as a hex value, and
the swatch on each grid row is `aria-hidden` because the status chip and the name
already carry the information.

**The server still accepts any `#RRGGBB`.** Constraining it there would mean the
palette could not change without a release, and this is a presentation decision.
The rule is enforced where it is a rule — in the UI that offers the choice.

## `If-Match` comes from the read, and 412 is not a field error

`useProject` returns `{ project, etag }` together, deliberately: fetching the tag
at submit time would read a value the user never saw, which defeats the guard.
The point is to detect that the project changed between the read they edited and
the write they sent.

A 412 goes to the banner and not onto an input, because nothing the admin typed
is wrong — the row moved underneath them, and the only useful next step is to
reload.

## Files

| File | What |
|---|---|
| `ProjectListPage.tsx` | the S-10 grid, status filter, search |
| `ProjectFormPage.tsx` | one page for create and edit, per `ResourceFormPage`'s precedent |
| `projectForm.ts` | the schema, the two translations, the palette, `isCodeEditable` |
| `projectQueries.ts` | the data layer; the detail read is hand-written for its `ETag` |
| `ProjectTabs.tsx` | B-017 · the shared header and the tab strip; three tabs since B-018 |
| `ProjectTeamPage.tsx` | B-017 · the Team tab grid, inline edits, add and remove |
| `projectTeam.ts` | B-017 · clear-versus-omit, the allocation summary, the role vocabulary |
| `projectTeamQueries.ts` | B-017 · the roster read and the three writes |
| `SlaMatrixPage.tsx` | B-018 · the SLA tab, grouped by task type, one Save for the whole grid |
| `slaMatrix.ts` | B-018 · `buildOverrides` and the drafts — which cells go on the wire, and why the rest must not |
| `slaMatrixQueries.ts` | B-018 · the matrix read with its `ETag`, and the `If-Match` replace |

`FormField` / `FieldGroup` / `ReadOnlyField` are imported from
`../resources/FormField` rather than copied a third time. B-011 duplicated them
from `features/tickets/create/` and **nominated them for promotion to
`components/ui/form-field.tsx`** — this is now the third caller, which makes that
promotion overdue. Still not done from here: `components/ui/` is Stream C's path
and B-010's `FilterDropdown` move is already waiting on their sign-off.
