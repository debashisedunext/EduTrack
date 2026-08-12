# Resource Master — list and form (B-010 S-07, B-011 S-08)

`/masters/resources` is the Admin's directory: ten columns, four filters plus
search, bulk activate/deactivate, and an export. `/masters/resources/new` and
`/masters/resources/:userId/edit` are the S-08 form.

| File | What it is |
|---|---|
| `ResourceListPage.tsx` | The S-07 screen |
| `columns.tsx` | The S-07 columns, in blueprint §7.4's order, including the Edit action |
| `useResourceFilters.ts` | Filter state, held in the URL |
| `BulkStatusBar.tsx` | The selection bar and the per-resource results dialog |
| `ResourceFormPage.tsx` | The S-08 form — Personal · Access · Org · Work · Projects |
| `resourceForm.ts` | The form's shape, its Zod schema and the two translations |
| `resourceQueries.ts` | The read with its `ETag`, and the two writes |
| `FormField.tsx` | Field wrapper and section — **duplicated from Stream C, see below** |
| `SkillsInput.tsx` | The chip input for S-08's "Skills/tags" |
| `ProjectAssignmentsEditor.tsx` | Project × per-project role rows |
| `TemporaryPasswordDialog.tsx` | The one time the generated password is readable |

## Things that are deliberate

**Filter state lives in the URL.** "Everyone reporting to Meera who is still
active" is a link a manager pastes into chat, and it cannot be if the state is
private to a component. Same rule the ticket list follows.

**`isActive` is genuinely three-valued.** The ticket list writes its boolean
filters as present-or-absent and never as `'false'`, because there
`isDelayed=false` and unset mean the same thing. Here they are different
questions — "show me the deactivated ones" against "show me everyone" — so
`isActive=false` is written to the URL and sent to the server. Unset means both:
the screen whose job includes reactivating people must not open with them hidden.

**Deactivated rows are dimmed, not hidden.** Same reason.

**Two explicit buttons, not one "Toggle status".** A mixed selection has no
toggle — half of it would go each way — and a bulk action whose effect depends on
rows the user cannot all see at once is the kind that gets clicked by accident.

**A clean run is a toast; anything else is a dialog.** "38 deactivated" is a
notification. "38 deactivated, 2 refused" is a list somebody has to act on, and
a notification that disappears after five seconds is not a list. The dialog names
each blocked resource, shows its open-ticket count, and says what to do next.

**Selection clears when a filter changes.** The rows are no longer on screen, and
acting on people you can no longer see is how a bulk deactivation surprises
somebody. Select-all is "this page" and adds to a selection built across pages
rather than replacing it.

**The export is a plain `<a href download>`, not a fetch.** The browser's own
download handling deals with `Content-Disposition`, progress and the file dialog;
a blob round trip would hold the whole export in memory to reproduce that badly.
`toQueryParams` builds the link's parameters and the grid's query, so the file
cannot be built from different filters than the screen — `cursor` and `limit` are
deliberately absent, because the export is every matching row.

**Projects render as codes with the name on the title.** Three project names per
row is a column nobody can scan.

**Timestamps are formatted in the viewer's zone.** Storage is UTC everywhere
(PLAN.md §3.1) and this is the presentation layer. The export is the exception —
its column is labelled `(UTC)`, because a spreadsheet leaving the building has no
viewer whose zone is knowable.

## The form (B-011)

**One page for create and edit.** They are one form — every field, validation
and section is shared, and the only differences are the verb, whether an `ETag`
is in play, and whether a password comes back. Two components would be the same
file twice, with one copy always slightly behind.

**`resourceQueries.ts` is hand-written, not the generated hooks**, for the two
reasons `calendarQueries.ts` already documents. `http()` returns a parsed body
and drops the response, so the `ETag` is unreachable through it; and orval omits
header parameters, so neither `If-Match` nor `Idempotency-Key` appears in the
generated signature. `PATCH /users/{id}` answers `428` without the first and the
create is unprotected without the second. Both hand-written parts go the day
orval emits header params and a response-aware mutator.

**The `ETag` is cached with the data it came from.** Fetching it at submit time
would read a value the user never saw, which defeats the guard entirely: the
point is to detect that the row changed between the read they edited and the
write they sent. `useResource` is `staleTime: Infinity` for the same reason — a
background refetch under an open form either discards typing or moves the tag
out from under the save.

**The form sends every optional key, as a value or an explicit `null`.** The
contract's third state — absent, "leave it alone" — is for callers patching one
field, and this form is not one of them: it holds the whole resource, so any key
it omitted would be a field the user cleared and the server silently kept.

**`weeklyOff` is genuinely tri-state.** `null` inherits the org working week and
is the default for everybody; `[]` means this person has no weekly off, which a
support rota is a real reason to want. The checkbox switches between them and
the day picker only appears for the second. Days are ISO — 1=Mon … 7=Sun — for
the reason B-023's note records at length.

**A per-project role is optional and blank means "same as their global role".**
Requiring it would force every membership to restate the person's role, and the
first time somebody's global role changed, every restatement would be a stale
override nobody knew was there.

**The temporary password is a modal, not a toast.** It is stored as an Argon2id
hash and no request can recover it. A notification that disappears after five
seconds, taking the only copy of an unrecoverable credential with it, is the
wrong control — and the dialog refuses Escape and overlay-click for the same
reason.

**Server errors land on the field that caused them.** A 409 naming `username`
and `email` sets both and focuses the first, rather than putting prose in a
banner above a form long enough that the message is below the fold. A `412` is
the exception: it is not the user's mistake and no field can carry "reload and
reapply", so it gets the banner.

### What the form deliberately does not do

- **Cycle detection beyond self-reference (B-012).** The manager picker excludes
  only the resource being edited. A→B→C→A is still expressible, and the server
  still accepts it. **The MSW mock refuses it and the real backend does not** —
  the mock describes the contract, so a frontend written against it will already
  handle the 409 when B-012 lands.
- **The reassignment wizard (B-014).** Deactivating somebody who holds open
  tickets is refused with a count, exactly as the grid refuses it.
- **Profile photo upload.** S-08 says "Profile photo"; the field takes a URL,
  because attachment storage is A-016 and there is nothing to upload to yet.

## `FormField.tsx` is duplicated, on purpose

It is a near-copy of `features/tickets/create/FormField.tsx`. By this codebase's
own rule the second caller is the trigger to promote a control to
`components/ui/` — which is exactly what B-010 did with `FilterDropdown`.
**`components/ui/` is Stream C's path**, that move already needed their sign-off,
and a second unilateral change to it in the same week is not something one
stream should do on its own branch.

So it is duplicated here and **nominated for promotion to
`components/ui/form-field.tsx`** the moment Stream C is asked. The shape is kept
identical to C's so the merge is a delete rather than a reconciliation.

`WeeklyOffPicker` needed no such treatment — it is B-023's, in
`features/masters/calendar/`, which is this stream's own path.

## `FilterDropdown` moved

It was at `features/tickets/list/FilterDropdown.tsx` and its own comment said "a
second caller earns this its own promotion to `components/ui/`". This screen is
the second caller, so it moved to `components/ui/filter-dropdown.tsx` unchanged,
with a Storybook entry. **`components/ui/` is Stream C's path — the move needs
their sign-off before merge.**

## Tests

`ResourceListPage.test.tsx` and `ResourceFormPage.test.tsx` against MSW, plus
`resourceForm.test.ts` for the translations on their own — the mapping between a
form and a request body is where a field silently stops being sent, and that is
unreachable behind a rendered page.

Two things worth knowing if you extend the list tests:

- **Rows are found by their select checkbox, not by the name in them.** A name is
  not unique on this grid — Anita Rao is a row *and* the Reporting Manager of
  four others — and the results dialog prints the same names again.
- **The grid is unreachable while the results dialog is open.** Radix marks the
  rest of the page `aria-hidden`, correctly, so row assertions come after the
  dialog is dismissed.

Which fixture resource has open tickets is derived from the mock db rather than
hardcoded: the mock assigns tickets by stage owner role, so hardcoding a name
would make these tests fail the next time somebody adds a stage — a fixture
change wearing a product bug's clothes.

Three for the form tests:

- **`SearchableDropdown`'s trigger takes its accessible name from the field
  label**, not from its placeholder, and the search box lives in the popover it
  opens. `getByRole('button', { name: 'Project assignments' })` is the trigger.
- **The role Select is a Radix listbox, not a native `<select>`.** `keyDown`
  Enter on the trigger opens it; the options are `role="option"`.
- **Radix needs four APIs jsdom does not implement** — `ResizeObserver`,
  `hasPointerCapture`, `setPointerCapture`, `scrollIntoView`. The `beforeAll`
  at the top of each file installs them.
