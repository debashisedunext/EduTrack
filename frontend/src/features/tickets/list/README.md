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
| `useTicketStageDots.ts` | B-051 — resolves each row's workflow template and builds its compact ribbon, in requests bounded by the master rather than by the page. |
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

## B-051 — the compact ribbon column, and what unblocked it

S-17's wireframe draws eight small dots per row — the ticket's stage progress
at a glance. C-014 shipped without it, and the reason recorded here was that a
grid mixing projects "cannot resolve one row's dot count without resolving that
row's own template, and doing that per row is the per-ticket-detail waterfall
S-20 deliberately collapsed into one aggregated call". Two exits were named:
a ribbon summary folded into `TicketListResponse`, or **"a stage-order lookup
keyed by project and task type that does not require fetching every
template"**.

B-041 shipped the routing table that makes the second one answerable, and the
column is now `journey` in `columns.tsx`, drawn by
`components/ribbon/RibbonDots.tsx` from data assembled by
`useTicketStageDots.ts`. **No contract change, no route, no migration and no
`PermissionMatrix` row** — `GET /masters/workflow-templates` and
`GET /masters/workflow-templates/{id}/mappings` are both `everyRole` already.

### The clause that mattered was "does not require fetching every template"

Meaning: not one request per row. `GET /masters/workflow-templates/resolution`
answers one project × task type pair, which is exactly right for S-13 tab 3,
where a person picks one. It was built here first and **measured worse**: the
fixture's generator draws `taskTypeId` from `int(1, 11)` across three projects,
so a page of 25 rows is up to 25 distinct pairs and 25 requests. One MSW round
trip costs something like half a second in the test rig, and the effect was
visible — running this directory's seven suites together went from one flaky
failure to six, all of them the documented Radix-popover timing flake tipping
over under the extra load.

What ships instead is bounded by the **workflow master** rather than by the
page: `listWorkflowTemplates` (already fetched here for the stage filter, so it
costs a cache read) plus one `mappings` call per template — three in the
fixture, and a number an Admin curates by hand thereafter. It does not grow
with rows, with paging, or with how many projects a reader can see.

The cost is `features/masters/templates/routeToTemplate.ts`, a **second
implementation of §4A.9's ladder** beside `TemplateResolver.java` and the
mock's `resolveTemplate`. Two things make that affordable: the server ships its
own ranking key (`TemplateMapping.specificity` is documented as "what the
resolver breaks ties on"), and `routeToTemplate.test.ts` asserts the client
agrees with `GET .../resolution` pair for pair against the fixture — so a drift
is a failing test rather than the wrong ribbon on the wrong ticket. If a
resolution endpoint that takes many pairs at once ever lands, that file goes
and this hook calls it.

### The column holds until the whole routing table has arrived

A correctness guard, not a loading nicety, and the one defect the integration
test caught. `routeToTemplate` falls through to the default template when no
rule matches — right, and what the server does. But *"no rule matched"* and
*"the rules have not arrived yet"* look identical from a half-loaded table, so
drawing dots early put **every** row on Standard Dev Flow's eight stages for a
frame: a wrong ribbon on most of them. `useTicketStageDots` returns nothing
until every `mappings` read has succeeded — and `isSuccess` rather than
`!isPending`, so a failed read holds the column too instead of quietly falling
back to the default. Either way the cell is an em dash.

### Three things the row payload cannot say, and none is guessed

- **Which earlier stage bounced.** `docs/prototype/index.html` paints a
  *completed* dot amber and hardcodes its index at 2. `TicketSummary` carries
  `iterationNo` and no transitions, so the amber mark here is the stage the
  ticket is in **now** — the prototype's own sentence for the colour is "amber
  means it has been sent back", which is a claim about the ticket.
- **Skipped and blocked stages.** Both are facts in `ticket_stage_transitions`.
  `buildCompactDots` produces four of `SegmentState`'s six values and no
  private union — `segmentState.ts` asked for exactly that.
- **A ticket whose stage code is not in its resolved template.** Almost always
  a template re-cut since the ticket started, and nothing versions a template
  yet (B-042). The cell renders an em dash. Eight hollow dots would claim the
  ticket has not started; an index would claim where it is.

### ⚠ The mock speaks two stage vocabularies, so most rows read an em dash there

Not a defect in this column, and worth knowing before anyone "fixes" it.
`db.stages` — Stream C's flat ribbon fixture, which the mock's filler ticket
generator draws `currentStageCode` from — says `DEVELOPMENT`, `DEPLOYMENT` and
`VERIFICATION`. The workflow template master says `DEV`, `DEPLOY` and `VERIFY`,
**following the database**, which seeds those in `V20260807_1700`. B-040 found
this and recorded it at the top of `mocks/db.ts`; reconciling it means renaming
codes that Stream C's reopen fixture and `ReopenDialog.test.tsx` assert on, so
it was left as a note rather than done across a stream boundary.

Against the real backend both halves say `DEV` and the question never arises.
Against the mock, a ticket sitting in `DEVELOPMENT` finds no such stage in its
template, and `buildCompactDots` correctly declines to place it. So
`TicketJourneyColumn.test.tsx` writes the template vocabulary onto the rows it
is about rather than pinning the divergence, which would pin a bug.

**It is not only this column.** The Stage filter a few sections down builds its
options from the same template stages, so filtering the mock to "Development"
already matches no row and has since C-013. **For Stream C:** one rename in
`db.ts`'s `STAGES` and `OPEN_STAGES` closes both, along with the fixtures that
assert on the old codes.

### Ownership

`columns.tsx` and `TicketListPage.tsx` are **Stream C's**, and this is Stream
B's edit in them — the arrangement TEAM-PLAN.md §6 sets out for weeks 10–11
("**joins C** — ribbon UI, segment states, compact variant"), the same one
B-050 records for `components/ribbon/`. The edits are one column definition,
one field on `ColumnRenderContext`, one hook call, and moving `renderContext`
below the list query so it can see the rows. **Flagged, not done quietly** —
the precedent B-027 through B-029 set in this same file.

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

## C-070 · the Module filter and the optional Module column

Blueprint line 986, in one sentence: "**Module filter and an optional Module
column** — off by default in the column chooser, because the grid is already at
its width budget, but filterable always. 'Every open Fees ticket' is the question
this list gets asked most once the field exists."

So the two halves are deliberately asymmetric. The **filter** is always there,
because it is what the field is for. The **column** is in the chooser and off by
default, because scanning across modules is the rarer act and the grid has no
width to spend on it. `useListPreferences` persists a reader's own selection, so
turning it on is a decision that sticks.

**The filter offers retired modules; S-19's picker does not.** Both are right.
Nothing new should be *raised* against a wound-down module — `ModuleGuard`
refuses it with a 400 — but "every open Transport ticket" is a fair question
about one, and hiding the row from the filter is the single thing that makes it
unanswerable. Retired rows are labelled `(retired)` rather than silently mixed
in. The column resolves names from the same unfiltered master for the same
reason: a cell that blanks out when a module is retired reads as missing data.

**The backend half is in this task too.** `TicketListSpecs.filters` accepted
`moduleId` and ignored it, and `TicketListService.toSummary` returned an
unconditional `null` for it — both honest while C-065's column did not exist, and
both a silent lie the moment it did. A filter that quietly returns every row is
worse than one that errors, because nobody checks a grid that looks full.

One thing worth knowing about the narrowing: the contract declares `moduleId` as
`int64` and `tickets.module_id` is an `INT`. A value outside int range is
matched as an impossible predicate rather than passed through `intValue()`,
which would truncate `4294967299` to `3` and hand back every Fees ticket to
somebody who asked for a module that cannot exist. There is an IT for exactly
that number.
