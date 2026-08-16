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
| `ClientContactsTab.tsx` | The Contacts tab — read-only; the writes are B-027's |
| `ClientProjectsPicker.tsx` | The project mapping, with the default inside the list |

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
- **Contact writes.** B-027's whole task; the tab reads and states B-028's
  primary-contact rule.
- **A delete button.** Tickets, contacts and project mappings all point at
  `clients`. Going away is the status control on the grid.

## What is not here yet

- **B-027** — the `client_contacts` child grid. Both the expand on the grid and
  the Contacts tab are read-only.
- **B-028** — unique code, valid emails, and the primary-contact gate.
- **B-029** — deactivating blocks *new* tickets. That rule lives on the ticket
  create path, not on this screen; S-32 only warns, with the count.
- **B-031…B-038** — the Excel wizard behind the disabled "Import from Excel"
  button. Replace it with a `Link` when the route exists.

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

**`/masters/clients` is the list; `/clients/:clientId` is the 360.** Two
screens at two paths, kept apart by the prefix exactly as `/masters/projects`
and `/projects/:id` already are. The 360 is not built; `App.tsx` points it at a
named placeholder so the link reads as an unbuilt screen rather than a broken
one.
