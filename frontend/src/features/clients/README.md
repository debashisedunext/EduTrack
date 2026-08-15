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

There is no `clientQueries.ts`. Every other master screen has one because the
generated client cannot express an `ETag` or an `Idempotency-Key`; none of
S-32's four operations take either, so the generated hooks are used directly.
The day `PATCH /clients/{clientId}` (B-026) lands, that file will be needed —
it takes `If-Match`.

## What is not here yet

- **B-026** — create/edit across four tabs (S-33). Its own page, not a dialog:
  §4B.2's field groups are six groups deep, which is B-016's project form
  rather than B-020's eight-field dialog.
- **B-027** — the `client_contacts` child grid. The expand here is read-only.
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

**`/masters/clients` is the list; `/clients/:clientId` is the 360.** Two
screens at two paths, kept apart by the prefix exactly as `/masters/projects`
and `/projects/:id` already are. The 360 is not built; `App.tsx` points it at a
named placeholder so the link reads as an unbuilt screen rather than a broken
one.
