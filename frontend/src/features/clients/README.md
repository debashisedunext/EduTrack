# features/clients

**Owner: Stream B · Ayush**

Client master and contacts. Screens S-32, S-33, and the S-34 import wizard's
entry point.

## What is here (B-025 · S-32)

| File | What it is |
|---|---|
| `ClientListPage.tsx` | The grid, its four filters, the row-expand and the bulk status action |
| `columns.tsx` | Blueprint line 946's nine columns, and the last-ticket formatter |
| `ClientBulkStatusBar.tsx` | The selection bar and the deactivation warning |
| `useClientFilters.ts` | Filter state, held in the URL |

The grid still uses the generated hooks directly — none of S-32's four
operations takes an `ETag` or an `Idempotency-Key`, which is what the generated
client cannot express. `clientQueries.ts` arrived with B-026, for the two
operations that do.

## What is here (B-026 · S-33)

| File | What it is |
|---|---|
| `ClientFormPage.tsx` | The four tabs, at `/masters/clients/new` and `/masters/clients/:clientId/edit` |
| `clientForm.ts` | The schema, the vocabularies, and the two translations |
| `clientQueries.ts` | The detail read with its `ETag`, and the two writes |
| `ClientProjectsPicker.tsx` | The project mapping, with the default inside the list |

## What is here (B-027 · S-33's Contacts tab)

| File | What it is |
|---|---|
| `ClientContactsTab.tsx` | The child grid — add, edit, remove, promote, and B-028's warning |
| `ContactEditorDialog.tsx` | The row editor, for both verbs |
| `contactForm.ts` | The schema and the two translations |
| `contactQueries.ts` | The three writes, and the invalidation that keeps the client's `ETag` fresh |

### Every button on the tab is `type="button"`, and it is load-bearing

The panel lives inside `ClientFormPage`'s `<form>`, and a button's default type
is `submit`. An "Add contact" without the attribute **saves the client** and
navigates away — doing the right-looking thing for one click and the wrong thing
for the other. `ClientFormPage.test.tsx` asserts it, because that is the only
place there is a form to submit; the tab's own test file renders it alone and
structurally cannot catch it.

The editor *is* a real `<form>` and is safe for a different reason: radix portals
`ModalContent` to `document.body`, so it is a sibling of the client's form rather
than a descendant, and its submit button belongs to it.

### A contact write invalidates the client, not only the contact list

`contactCount` and `hasPrimaryContact` are fields of `ClientDetail`, and
`ClientController` takes the `ETag` from that record's `hashCode` — so **every
write here moves the client's tag**. Refreshing only the contact list would leave
the form holding a stale one, and the admin's next Save on the Identity tab would
come back `412`: "somebody else saved this client while you were editing", about
a change they made themselves on this screen seconds earlier.

That is also why the contact routes take no `If-Match` of their own — the tag
would have to come from a collection read that has none. The parent's
precondition is what catches a stale editor.

### Removed contacts are shown, greyed, and cannot be restored

The grid reads `?includeInactive=true` where every picker reads the default.
Removal deactivates, so hiding the row would mean watching somebody vanish with
no way to tell "removed" from "never existed" — and no way to see the address is
still spoken for. There is **no un-remove**: `is_active` is not in the server's
`UPDATE`, so an edit structurally cannot resurrect a contact, and somebody who
returns to the client is added again. A removed contact is still *editable*,
because correcting a misspelt name so a historical ticket reads properly is a
real thing to want.

### Removing the primary is allowed, and the dialog says what it costs

**A client without a primary contact is not selectable on a ticket** (§4B.2).
That is warned about, never enforced here: the person may have left, and a
contact who cannot be removed until somebody else is promoted reads as a broken
button. The confirmation states the consequence before the click rather than
leaving it to be discovered on the ticket create form.

### The tabs are state, not routes — the opposite of `ProjectTabs`

S-10's four tabs are four routes because each owns a different resource with a
different `ETag`; no shared parent read could have served both the project and
its SLA matrix.

S-33's four tabs are **one resource behind one Save button**. Identity,
Commercial and Projects & SLA are all fields of `ClientWriteRequest`, saved by
one `PATCH` against one tag. Routes would mean either four reads of the same
client or losing every unsaved edit on a tab change — and a form that discards
what you typed when you go to check something on another tab is worse than one
long page.

Two consequences the tests pin:

- **The panels are `hidden`, never unmounted.** react-hook-form unregisters an
  input that leaves the DOM, so unmounting a tab would drop its values from a
  payload that sends every field — invisible until somebody edits a client's
  address and finds their notes cleared.
- **A server error opens the tab its field is on.** `tabForErrors` takes the
  first bad field in *schema* order, not the order the server serialised;
  otherwise a 400 naming `timezone` while the admin is on Commercial marks an
  input nobody can see and the save reads as having done nothing.

### What the form deliberately does not offer

- **An SLA policy picker.** Nothing reads `clients.sla_policy_id` — C-012's
  ladder resolves org → project → task type and never consults it. The stored
  value is shown read-only and the tab points at B-018's matrix. A control whose
  only effect is to write a number nobody looks at is worse than no control.
- **A delete button.** Tickets, contacts and project mappings all point at
  `clients`. Going away is the status control on the grid.

## What is here (B-031, B-032, B-033 · S-34's first three steps)

| File | What it is |
|---|---|
| `import/ClientImportPage.tsx` | The wizard at `/masters/clients/import` — five steps named, steps 1 to 3 working |
| `import/UploadDropzone.tsx` | §4B.3's drag-drop and file picker |
| `import/importQueries.ts` | The template download, the pre-flight check, and the upload |
| `import/MappingStep.tsx` | B-033 · step 3's table — one row per column the import accepts |
| `import/MappingPresets.tsx` | B-033 · §4B.3's saveable presets, and what applying one could not place |
| `import/columnMapping.ts` | B-033 · step 3's rules as pure functions |

**Steps 4 and 5 are on screen and disabled, not hidden.** Hiding them would make
the page look finished and leave the user to find out at the end of step 3 that
there is no step 4 — and it would drop §4B.3's actual promise, which is that
nothing is written until a per-row preview has been seen. That promise is the
reason this is a five-step wizard rather than one upload button, and it is worth
making before somebody starts typing four hundred rows.

### B-032 · the drop zone is a label around a real file input

Drag and drop cannot be done from a keyboard and is awkward with a screen reader.
Building the zone as a `<div>` with handlers means reimplementing focus, Enter,
Space, the accessible name and the platform file dialog — and reimplementing them
slightly wrong. A `<label>` wrapping an `<input type="file">` gets all five for
free, and the drop handlers sit on top of a control that already works without
them. The input's value is cleared after each pick, so choosing the *same* file
again still fires `change` — the ordinary way somebody retries after fixing the
spreadsheet, and a silent no-op without that line.

### The pre-flight refusal is about latency, not security

`rejectionReason` refuses the wrong type or an oversized file before anything is
sent. The server refuses them too and is the only enforcement that counts; what
this buys is telling somebody their 40 MB export is too big **without uploading
40 MB first**. The row limit is deliberately not checked here — rows are not
knowable without parsing, and guessing from the byte count would refuse a
large-but-legal file or wave through a small illegal one.

`.xls` is not in the picker's `accept` list, which is the visible half of a
backend decision documented at length in `feature/imports/README.md`: it is a
binary container with no XML to stream, so reading it means the whole-workbook
reader §4B.3's step 2 exists to avoid. The refusal names the conversion.

### The chosen `File` is held, which is what makes the sheet selector work

Choosing another sheet re-posts the same file with `?sheet=`, plus the `uploadId`
it supersedes so the server releases that staging slot. The alternative — asking
the server to re-read a copy it kept — means holding the bytes of every open
upload for the staging TTL, and the browser is already holding this one.

### ⚠ `uploadImportFile` is hand-written because of a defect, not a preference

The same one `features/tickets/attachments/uploadTicketAttachment.ts` documents:
orval pins `Content-Type: multipart/form-data` on the generated call and
`api/http.ts` spreads caller headers *after* the branch that deliberately omits a
content type for a `FormData` body, so the generated header wins — and a
multipart body without a `boundary` parameter is unparseable. Spring's
`@RequestPart` answers 400 or 500 for every upload. **Two features now carry the
same workaround**, which is the argument for the one-line fix in `api/http.ts`
(Stream D's). `uploadImportFile.test.ts` is the regression guard; delete both
wrappers on the day that lands.

**The download does not use the generated `useDownloadImportTemplate`,** for two
structural reasons rather than a preference. It is a `useQuery`, so it would
fetch a workbook on mount and again on every window focus — a download is an
event, not cached state. And `http()` parses a body and drops the `Response`, so
it cannot return the file *name*; this hook reads `Content-Disposition` off a
plain `fetch`, exactly as `useClient` reads `ETag` off one, and for the same
reason. Delete both the day `http()` exposes response headers.

### B-033 · the mapping never goes to the server, and does not need to

Step 3 adds **no** write of the mapping. `/validate` and `/commit` both take
`mapping` in their own body — which the contract has said since B-030 — so a
parked server copy would be a fifth piece of wizard state to keep in step with the
other four, and the dry run needs the mapping in its own request regardless
because the user can go back and change it. The mapping lives in
`ClientImportPage`'s state and is handed down.

What step 3 *does* read from the server is the two things the browser cannot know:
**which columns the import accepts** (`useDescribeImportSchema`) and the saved
presets. A field list hardcoded here would be a second declaration of the client
master — the exact thing B-030's registry exists to prevent — and `required`, which
is the whole basis of §4B.3's "unmapped required columns block Next", is derivable
from nothing step 2 returns.

### One row per *our* field, not per *their* column

§4B.3 asks for "a manual override dropdown per column", and the direction it has
to be read in is the mapping's own: target field → source column. Rendered the
other way round — a row per column in the file, each with a dropdown of our fields
— the screen **cannot show a required column that is missing from the file
entirely**, because there is no row for a column that is not there. That is the one
case the step exists to catch.

Twenty native `<select>`s, not Radix ones, for the reason `TaskTypeListPage`
records: a plain option list with no search, grouping or custom rendering is what
the platform control already does, keyboard- and screen-reader-correct for free —
and twenty Radix selects would be twenty portals on one screen.

### Applying a preset is a function, because it can fail partially

`applyPreset` drops the entries whose source column this file does not have, and
`MappingPresets` says which ones. Both halves matter:

- **Carrying a stale entry over is the worse bug.** The mapping would name a
  heading that is in no dropdown on screen, so the row would render as unmapped
  while `missingRequiredFields` counted it as mapped — and Next would be enabled
  over a required column that is not mapped at all.
- **Dropping it silently is the other one.** A user who picks *CRM export* and
  watches three fields fill in out of eleven cannot otherwise tell a preset that
  was always partial from one whose columns have since been renamed.

A target field the schema no longer declares is dropped the same way. The server
refuses to *save* one (`import-unknown-field`, 422) — this covers rows saved before
a field was renamed, which that refusal cannot reach retroactively.

Matching is exact, for `HeaderMatcher`'s reason: normalising "Support Email" onto
"Email" would put the helpdesk address in the account contact field, in a mapping
the user was shown and skimmed.

### Three smaller calls worth knowing about

**Clearing a `<select>` removes the key** rather than storing `''`. Absence needs
one representation: an empty string still counts in `Object.keys(mapping).length`,
which is what the "n of m mapped" summary reads, and the server strips it on save
— so leaving it in would make the screen disagree with the preset it just saved.

**Two fields reading one column is allowed and named, not blocked.** One `Email`
column feeding both email fields is a real file and the mapping can express it. It
is surfaced because the other way it happens is a slip on a misread row.

**Columns the import will not read are reported, and are not an error.** A file
exported from another system carries columns this import has no home for. Said
anyway, because `Account Manager` is the live case: `ClientImportSchema`
deliberately has no column for it (a foreign key, and a spreadsheet carries only a
name), so somebody who filled it in learns that now rather than after the commit.

## What is not here yet

- **B-028's gate refused by a *server*.** B-028 landed the rest of it: the S-19
  client dropdown reads `hasPrimaryContact` off every row and renders such a
  client **listed, greyed and unselectable** with the reason beside it
  (`getOptionDisabled` on `SearchableDropdown` — Stream C's component, one
  additive prop, flagged). What is still missing is the backstop, and it is
  missing because `POST /tickets` has no server behind it at all: the
  obligation is written into that operation's contract description and flagged
  for Stream C. Shown-and-refused rather than filtered out, because a client
  that is simply absent from a dropdown is indistinguishable from a dropdown
  that has lost its data — and the person raising the ticket is usually the one
  who can go and fix the master.
- **B-029** — deactivating blocks *new* tickets. That rule lives on the same
  path, alongside B-028's, and is flagged in the same place; S-32 only warns,
  with the count.
- **B-034…B-038** — the rest of the Excel wizard. B-031 landed the route, the
  step rail and the template download; B-032 the upload, the sheet selector and
  the columns-found summary; B-033 the mapping table, the presets and §4B.3's gate
  on Next. The dry run and the commit are still to come, and the screen says so
  where step 3's Continue button is — together with the sentence that matters most
  at that moment: *the mapping is complete, and nothing has been written*.

## Two things that look like inconsistencies and are not

**This screen warns where S-07 refuses.** The resource grid blocks a
deactivation that would orphan open tickets. A client's tickets are not
orphaned — blueprint §4B.2 says deactivating blocks new tickets and *never
hides historical ones* — so there is nothing to fix before proceeding, and the
count is there to inform rather than to veto.

**A prospect renders as active, and the status chip is not a boolean.** B-026
added §4B.2's third state, and `isActive` derives as `status !== 'INACTIVE'` so
that a prospect stays in the ticket form's client dropdown. That makes a
prospect and a contracted client identical to a boolean, which is why
`columns.tsx` reads `status` and the chip has three words rather than two.

**B-028 · and the server was not doing that.** It projected `isActive` the wide
way and *filtered* `?isActive=true` as `status = 'ACTIVE'`, so every prospect
was missing from the S-19 dropdown against a real backend while showing up
under `npm run dev` — the MSW mock had it right and its comment claimed to be
matching the server. Fixed in `ClientQueryRepository`; `ClientMasterIT` pins it,
because a unit test that mocks the repository cannot see a predicate.

**Three forms validate an email and only one rule does the deciding.**
`@/lib/email` is the browser's copy of the server's `EmailFormat` — zod's
`.email()` accepts `sara@acme`, which the server now refuses and B-035's import
always refused. A client-side rule looser than the server's is the worse
direction of the two: the form says the field is fine and the save comes back
with an error on it.

**B-029 · one warning, two ways to deactivate, and one rule for the block.**
`DeactivationWarningDialog` moved out of `ClientBulkStatusBar` because S-33's
Status select reaches `INACTIVE` in two clicks and used to save without a word —
the same consequential act as S-32's bulk bar, on a page that had already loaded
the count it should have been showing. Two dialogs would be two chances to
describe one consequence differently. The bulk warning also reads every row the
grid has *seen* rather than the current page: this grid builds a selection
across pages on purpose, and a client ticked three pages ago went through
unwarned.

**`ticketEligibility.ts` is where both new-ticket gates live** — B-028's "needs a
primary contact" and B-029's "not deactivated" — because a second derivation
beside the first is how a rule ends up with two answers, which is what
`FieldValidators`, `ProjectRoles` and `PasswordComplexity` all exist to stop and
what B-028 found happening anyway. `POST /tickets` has no controller, so until
C-013 mounts it that module is the only enforcement in the system; the
obligation is stated in `createTicket`'s contract description and flagged.

**`/masters/clients` is the list; `/clients/:clientId` is the 360.** Two
screens at two paths, kept apart by the prefix exactly as `/masters/projects`
and `/projects/:id` already are. The 360 is not built; `App.tsx` points it at a
named placeholder so the link reads as an unbuilt screen rather than a broken
one.
