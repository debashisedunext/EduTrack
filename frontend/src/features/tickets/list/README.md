# S-17 Ticket List (All Tickets) — C-014, saved views C-015, bulk select C-017

Filters, sticky header, density toggle, column chooser — the base grid
`STREAM-C-TICKETS.md` scopes to this task. Row-scoped server-side by
`ScopeResolver`, same as every other ticket query; nothing here re-filters
on top of what the API already narrowed.

| File | What it is |
|---|---|
| `TicketListPage.tsx` | The screen. Route `/tickets`. |
| `useTicketListFilters.ts` | Filter state, read from and written to the URL; `applyFilters` (C-015) sets several keys atomically. |
| `FilterDropdown.tsx` | Feature-local, nullable, clearable dropdown — the eight filter chips in the wireframe's second row. |
| `DateRangeFilter.tsx` | The "Dates▾" filter — `dueFrom`/`dueTo` against the planned close date. |
| `SavedViewsMenu.tsx` | C-015 — the six fixed S-17 saved views, replacing the old disabled stub. |
| `columns.tsx` | Column definitions, cell renderers, the level/status chip variant maps. |
| `useListPreferences.ts` | Density and column visibility, persisted to `localStorage`. |
| `ColumnChooserMenu.tsx` · `DensityToggle.tsx` | The "⚙ Columns" popover and the comfortable/compact toggle. |
| `bulk/bulkActions.ts` | C-017 — who may act, and which of a selection each action can reach. Pure functions, no React. |
| `bulk/TicketBulkActionBar.tsx` | The selection bar, the three dialogs, and the refusal list. |
| `bulk/useBulkTicketActions.ts` | The three mutations, hand-written for the `Idempotency-Key` header orval drops. |

## Bulk select is PM and Admin only, and the grid is not what enforces it

C-017. `canBulkAct` decides whether the checkbox column and the action bar are
drawn at all — and it is deliberately the *shown*/*allowed* split
`commentPermissions.ts` documents. The three handlers refuse a Support,
Developer, QA or Deployment caller with `403` before reading a single ticket
id, so a request built past a hidden toolbar is refused all the same. Hiding
the control stops the product offering something that will be refused; it is
not the rule.

Three consequences worth knowing before changing anything here:

- **A summary history row would be a defect, not an optimisation.** Every
  action writes one entry *per ticket*. The per-ticket audit trail is the point
  of having one, and a batch of fifty is fifty entries.
- **Selection survives paging**, so `closableIds` treats ids it cannot see on
  screen as closable rather than dropping them. A selection assembled across
  three pages must not silently shrink because two of those pages have
  unmounted.
- **Refused rows stay ticked.** `selectionAfter` releases only what succeeded.
  Clearing everything would hide the failure the moment the dialog closed, and
  the grid refetch blends those rows back in unfindably.

The result dialog renders **only when something was refused**. "38 closed" is a
modal whose whole content the reader already knows; "38 closed, 2 refused" is a
list somebody has to act on.

### The two new endpoints, and who owns them

Reassign reuses `POST /tickets/bulk-reassign`, which S-24 had already put in
the contract. Level and close needed `PATCH /tickets/bulk-level` and
`POST /tickets/bulk-close` — **new paths in Stream D's
`contracts/openapi.yaml`**, with their MSW handlers and two
`check-conventions.py` exemptions. Flagged for Stream D rather than done
quietly; the same one-argument-wide precedent B-027 through B-029 set in the
other direction inside `TicketListPage.tsx` itself. The existing
`bulk-reassign` mock gained the PM/Admin guard it did not have.

One request per action, not one per row: fifty ticked tickets would otherwise
be fifty round trips, fifty interleaved planned-close-date recomputations, and
a partial result nobody can reconstruct. `/clients/bulk-status` records the
same reasoning.

## Filters live in the URL, not component state

`TopBar` (C-005) already navigates here as `/tickets?q=…` for its global
search box — that only works if this screen reads `q` out of the URL rather
than owning it privately. Every other filter follows the same rule for the
same reason a saved view (C-015) will want it: a filtered grid is a link a
manager can paste into chat and get back exactly what they were looking at.
`useTicketListFilters` is the single place that parses and writes the eight
filter params plus `q`; `Reset` clears the filter row but leaves `q` alone; it
is a header control the wireframe draws separately from the filter row.

## The compact ribbon column is deliberately not here

S-17's wireframe draws eight small dots per row — the ticket's stage
progress at a glance — but it is not named in C-014's own backlog line
("filters, sticky header, density toggle, column chooser"), and building it
turned out to need more than this screen already fetches.

The prototype's own version (`docs/prototype/index.html`) computes it from
three plain numbers per row — stages completed, current stage index, whether
any stage was reworked — which look derivable from `Ticket.currentStageCode`
and a stage's position in sequence. The complication is that the sequence
itself is not one fixed list: `GET /masters/workflow-templates` accepts
`projectId` *and* `taskTypeId`, and a template's own `POST` doc says stages
in use are "deprecated, never deleted" specifically because live tickets keep
the template version they started on. A grid mixing tickets from several
projects — which is exactly what Admin and PM see — cannot resolve one row's
dot count without resolving that row's own template, and doing that per row
is the per-ticket-detail waterfall S-20 deliberately collapsed into one
aggregated call. Today's mock only ever seeds one template, so the gap does
not show yet; it would the day a second one exists.

Decided with the developer rather than worked around silently: ship the grid
without it and pick this back up once there is a cheap way to answer "what
stage sequence does this row's ticket belong to" — either a per-ticket ribbon
summary folded into `TicketListResponse` the way S-20 folds ribbon into
`TicketDetailResponse`, or a stage-order lookup keyed by project and task
type that does not require fetching every template. Worth raising with
Stream D when C-015 or C-016 next touch this contract.

## `meta.totalCount` is optional, and the pager treats it that way

`Meta.totalCount`'s own doc comment: "Present only where a count is cheap.
Never computed live over tickets." — the same rule CLAUDE.md states for
dashboards, extended to this list. The mock's `paginate()` always fills it
in, so it is tempting to build the footer as if it is guaranteed; against the
real API it may not be. The footer shows `Rows 12–36 of 263` when it is
there and `Rows 12–36` when it is not, and pagination itself never depends on
it — Previous/Next work off `meta.nextCursor` and `meta.hasMore` alone.

## Cursor pagination has no page numbers

CONVENTIONS.md is explicit that offset paging over a table under write
traffic skips and repeats rows, so `?cursor=` is what the contract offers.
The wireframe draws `‹ 1 2 3 … ›`; this ships Previous/Next instead, backed by
a stack of the cursors visited so Previous can step back through pages
already seen. A filter change clears the stack — paging state from the old
result set has nothing to do with the new one.

## The Stage filter's options come from workflow templates, not a stages endpoint

There is no `GET /masters/stages`. Stage names exist only nested inside
`GET /masters/workflow-templates`, so the filter calls that unfiltered,
flattens every template's `stages[]`, and dedupes by `stageCode` — reasonable
for populating a *filter list* (picking a stage no ticket happens to be in
just returns zero rows) in a way it would not be for the ribbon column above,
where each row needs its own specific answer rather than the union of all of
them.

## Level options come from the priority master, in its order

Same rule C-013's `LevelPicker` follows: which levels exist and what order
they render in is the priority master's call, not a hardcoded enum. Status
has no equivalent master, so its eight options come straight from the
contract's `StatusCode` enum, labelled in `columns.tsx`.

## Worth knowing for the other streams: chaining two popover picks in a test

`FilterDropdown` and `SearchableDropdown` are both Radix `Popover`/dialogs
that restore focus to their own trigger a frame after closing —
`CreateTicketPage.test.tsx` already documents this for `SearchableDropdown`.
It is a plain flake there because nothing else reopens in that same frame.
Here, picking one filter and immediately opening a *second, different*
filter's popover races that restoration: if the second popover is already
open when focus jumps back to the first trigger, Radix reads that as focus
leaving the second popover and dismisses it before the click lands. It only
shows up under real CPU contention — running this suite alongside the other
nine, not alone — which is why it took several full-suite runs to catch.
`pickFilterOption` retries "reopen, then click the option" as a single
`waitFor` unit rather than two, so a dismissal mid-sequence just gets retried
from the top. The list's own "counts active filters" test sidesteps the
question entirely by driving both filters through the URL instead of two
chained picks, since chaining them was testing Radix's timing more than this
screen's behaviour.

## Deliberately not here

| Not built | Owner |
|---|---|
| Compact ribbon column | See above — no owner yet, needs a contract answer first |
| Bulk select → reassign / change level / close | C-017 |
| Export CSV / PDF | A-064 (Stream A's export engine) — not S-17's job at all, confirmed against the backlog before assuming it belonged here |

## C-016 — Row colour cue is a left border, not a background tint

PLAN.md's own line is literal: "delayed rows get a soft amber left border;
critical get soft red" — nothing about shading the row itself. `columns.tsx`
exports `rowCueClassName(ticket)`, a pure function `TicketListPage` passes
straight to each `TableRow`'s `className`, using the same `level-high` /
`level-critical` tokens the Level chip and the ribbon already draw from
(`tokens.css` calls the un-tinted `DEFAULT` shade "3:1 UI threshold... for
icons, borders and chip backgrounds", which is exactly this use). Critical
wins when a row is both delayed and Critical — in practice the SLA scanner
promotes a delayed ticket to Critical itself (blueprint §7.2/§7.4), so the
mock's own ticket generator sets `level: delayed ? 'CRITICAL' : pick(LEVELS)`
and a delayed-but-not-Critical row essentially only exists in the gap before
that scan runs. The colour is additive, not the only signal — the Level chip
column and the PCD column's ⚠ icon already say the same thing in text/icon
form, so this reads fine for colour-blind users and on a printed screenshot.

## C-015 — Saved views are fixed presets, not persisted searches

Blueprint §7.5/S-17 names exactly six views — My Open, Due Today, Overdue,
Unassigned, Reopened, Closed This Month — with no elaboration on filter
criteria and no `SavedView` schema anywhere in the contract. Rather than
inventing backend persistence nobody asked for, these ship as six **fixed,
built-in presets** over the same URL-based filter state C-014 already built —
`SavedViewsMenu` picks one, `useTicketListFilters.applyFilters` replaces the
filter row with its recipe in one URL update, same as `resetFilters` does for
Reset. There is nothing to save, rename or delete, and nothing server-side to
build for this task.

| View | Recipe (all other filters cleared) |
|---|---|
| My Open | `assigneeId = <me, via useGetMe()>`, `excludeClosed = true` |
| Due Today | `dueFrom = dueTo = today` (local date) |
| Overdue | `isDelayed = true` |
| Unassigned | `unassigned = true` |
| Reopened | `reopenedOnly = true` |
| Closed This Month | `status = CLOSED`, `closedFrom = 1st of this month`, `closedTo = today` |

`isDelayed` and `reopenedOnly` were already full round-trips through the
contract and the mock — C-014 just never put a UI control in front of them.
The other three needed small additive contract changes (see below) because
the contract had no way to express "no assignee", "not closed", or a date
range on `actualCloseDate` rather than `plannedCloseDate`.

**Active-view highlighting is computed, not stored.** `SavedViewsMenu`
compares the current `filters` (from the URL) against each recipe's full
`TicketListFilters` object, key by key, ignoring `q`; a match highlights that
item and shows its name on the trigger. There is no separate "which view is
selected" state to fall out of sync with the URL — picking a view, editing a
filter chip afterwards, pasting a `/tickets?...` link, or hitting Reset are
all just different ways of landing on a `filters` object, and the menu reacts
to whichever one is current.

**My Open needs `useGetMe()` to resolve first.** `TicketListPage` reads the
signed-in user's id and passes it down; until it resolves, the "My Open" item
renders present-but-disabled rather than applying `assigneeId=undefined`
(which the contract would happily accept as "no filter" and silently show
everyone's tickets under a view named "My Open").

### Contract additions — ⚠ needs Stream D sign-off

`contracts/openapi.yaml`'s `GET /tickets` gained four optional, additive
boolean/date params, same style as the existing `isDelayed`/`isClientRaised`/
`reopenedOnly`/`dueFrom`/`dueTo`:

- `unassigned` (boolean) — true to return only tickets with `assigneeId IS NULL`. `assigneeId` only does equality-to-a-specific-user; there was no way to ask for "nobody".
- `excludeClosed` (boolean) — true to exclude `status = CLOSED`. `status` is single-value equality, so "assigned to me AND not closed" (My Open) had no way to express the second half.
- `closedFrom` / `closedTo` (date) — mirrors `dueFrom`/`dueTo` exactly, but filters `actualCloseDate` instead of `plannedCloseDate`. Closed This Month needs the date it actually closed, not its planned close date.

The client was regenerated (`npm run api:generate`) — only
`listTicketsParams.ts` and `tickets.zod.ts` changed, pattern-only diffs, `tsc
-b` and the full test suite clean. Nothing under `api/generated/` was
hand-edited.

### The `dueFrom`/`dueTo` mock gap C-015 also had to fix

C-014 declared `dueFrom`/`dueTo` in the contract and even built the "Dates▾"
filter around them, but `frontend/src/mocks/handlers/tickets.ts`'s `GET
/tickets` handler never actually read them — they matched the OpenAPI shape
and did nothing. Invisible until something depended on the filter actually
working, which "Due Today" does. Fixed alongside the three new params, same
inclusive, date-only range style; the mock db.ts fixture ticket
`CRM-26-00347` (`plannedCloseDate` 2026-08-13, `actualCloseDate`
2026-08-14T16:30) is what `mocks.test.ts`'s new filter tests pin against, and
is deliberately why the `dueFrom`/`dueTo` and `closedFrom`/`closedTo` tests
use different days — a handler that swapped the two date fields would still
pass a test that used the same day for both.

### No Storybook entry for `SavedViewsMenu`

It is ticket-list-specific — six hardcoded view names and a recipe shape
(`TicketListFilters`) that only exists in this folder — not a generic,
reusable control the way `FilterDropdown`'s search-list-with-clear pattern
arguably is. CLAUDE.md's Storybook rule is for the shared library other
streams consume (`components/ui/`, `components/ribbon/`); this stays a
feature-local component next to `TicketListPage.tsx`, same tier as
`ColumnChooserMenu.tsx` and `DensityToggle.tsx`, neither of which has a story
either.

**`bulk/TicketBulkActionBar.tsx` carries the same exemption**, and more
plainly: the bar hardcodes the three S-17 actions and each dialog is shaped by
one specific request body. S-07's `BulkStatusBar` is the proof that this does
not generalise — it batches the same *idea* over resources and shares no props
with this one. A shared bulk bar would have to be a slot renderer with a
`children` bag of buttons, which is a `<div className="flex gap-3">` with extra
steps.
