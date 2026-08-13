# Project Master — B-016 · S-10

`/masters/projects` · `/masters/projects/new` · `/masters/projects/:projectId/edit`

The list and the create/edit form. S-10 describes four tabs and this is the
first: **Team is B-017**, **SLA is B-018** and **Settings is B-019**, each with
its own contract path and its own task. They are not stubbed here — three empty
tabs would make three unbuilt screens look built.

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

`FormField` / `FieldGroup` / `ReadOnlyField` are imported from
`../resources/FormField` rather than copied a third time. B-011 duplicated them
from `features/tickets/create/` and **nominated them for promotion to
`components/ui/form-field.tsx`** — this is now the third caller, which makes that
promotion overdue. Still not done from here: `components/ui/` is Stream C's path
and B-010's `FilterDropdown` move is already waiting on their sign-off.
