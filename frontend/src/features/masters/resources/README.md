# Resource Master — list (B-010, S-07)

`/masters/resources`. The Admin's directory: ten columns, four filters plus
search, bulk activate/deactivate, and an export.

| File | What it is |
|---|---|
| `ResourceListPage.tsx` | The screen |
| `columns.tsx` | The S-07 columns, in blueprint §7.4's order |
| `useResourceFilters.ts` | Filter state, held in the URL |
| `BulkStatusBar.tsx` | The selection bar and the per-resource results dialog |

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

## `FilterDropdown` moved

It was at `features/tickets/list/FilterDropdown.tsx` and its own comment said "a
second caller earns this its own promotion to `components/ui/`". This screen is
the second caller, so it moved to `components/ui/filter-dropdown.tsx` unchanged,
with a Storybook entry. **`components/ui/` is Stream C's path — the move needs
their sign-off before merge.**

## Tests

`ResourceListPage.test.tsx`, against MSW. Two things worth knowing if you extend
it:

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
