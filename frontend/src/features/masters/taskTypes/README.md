# S-11 Task Type Master — B-020

`/masters/task-types` — one route. The grid, plus a create dialog and an edit
dialog.

| File | What it is |
|---|---|
| `TaskTypeListPage.tsx` | The grid and both dialogs |
| `taskTypeForm.ts` | Form state, validation and the two wire mappers — pure, no React |
| `taskTypeQueries.ts` | The data layer |

No `/:id` route, because a task type is eight fields. B-016's project form earns
its own page by having twenty; S-12's priority master will be this shape again.

## There is no delete, so the retire control has to say what it does

Three foreign keys point at `task_types` without cascades, so retiring is the
only way a type goes away. What that costs is not visible from the row, and both
halves of the cost are somewhere else in the product:

- the type leaves the create-ticket form's picker, and
- it leaves **every project's SLA matrix**, which is built from the active types.

So the dialog states both in words, and `ticketCount` is on every grid row
rather than only in the confirmation — the call B-015 made with `userCount` and
B-014 with `openTicketCount`. The button says "Retire", not "Delete": a Delete
that deactivates is the kind of label somebody later "fixes" into a real delete.

`useUpdateTaskType` invalidates `/projects` as well as the task-type list, or a
project's SLA grid would keep rendering a row for a type that has just gone.

## The colour is a palette, not a hex box

CLAUDE.md: never introduce a colour that is not a blueprint §12.1 token. The
server can only check the *shape* (`#RRGGBB`) because it has no palette, so the
constraint lives here, where the choosing happens — the eight-colour
colour-blind-safe chart palette, the same eight B-002 cycled through when it
seeded the eleven. `taskTypeForm.test.ts` fails if that list drifts, because
nothing else would catch it.

The swatch on each grid row is `aria-hidden` and the name beside it is the
label. §12.1: never colour alone.

## The two mappers are where a quiet mistake would live

`toPatchRequest` sends the **whole form** on every save.

- `code` goes back deliberately. Re-sending the stored value is a no-op on the
  server; sending it at all is what makes a *changed* one refusable, so a caller
  who believed they had renamed the code is not told the save succeeded.
- `icon` and `defaultSlaHrs` go as explicit `null` when blank rather than being
  omitted. Absent and null mean different things to the server's patch DTO —
  which is a POJO rather than a record for exactly that reason — and omitting
  them would make clearing either one impossible from this screen.

`toFormValues` → `toPatchRequest` is round-tripped in the test, because anything
that pair loses is silently dropped from the row on the first unrelated edit.

## Key off `code`, not `name`

`name` is display text an Admin can now change from this screen. `code` is
immutable and is what the Excel import matches on.

**This is not hypothetical.** `features/tickets/create/ticketForm.ts` decides
which types make the Client field mandatory (§4B.2) by matching on the display
name, and its own comment already says a rename would silently disable the rule.
`code` is on the contract now so that match can move. **Flagged for Stream C** —
their path, not changed from here. In the meantime the server refuses a
duplicate `name`, which closes the wider version of the same hole.
