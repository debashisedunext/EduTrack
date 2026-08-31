# Dashboard Rework — Dev 1 backlog

**Foundations and the Today tab.** Boundary rules live in [`Dashboard-Rework-Split.md`](Dashboard-Rework-Split.md); behaviour lives in [`Dashboard-Rework-Plan.md`](Dashboard-Rework-Plan.md); appearance lives in `docs/prototype/index.html`. Where this file and the prototype disagree on how something looks, the prototype wins.

You own the seams. Two of your PRs (2 and 4) unblock Dev 2, and one (5) unblocks both of you — those three come first, before anything that only serves your own tab. The one thing you do **not** own is the API contract: that is Dev 2's, start to finish.

## Day one, before any code

1. **Hand Dev 2 your contract requirements, in writing, before anything else.** Dev 2 owns `contracts/openapi.yaml` outright and authors the whole delta today — including your `/dashboard/today` response and the seven new `GET /tickets` params. Your job is to specify what those two must carry: every card and sub-figure on the Today tab, the MIS row shape, the own-work variant, and each list param with its type. Then get out of the way. You never open that file, and you never run `npm run api:generate` — you rebase and pick up the generated client.
2. **One `/notify-stream` message to Divyansh** carrying all three Stream C asks at once: the `ticket_cycles.started_at` / `finished_at` stamping at transition time, the new `TicketListController` params, and the `TicketDetailTabs` reduction to a wrapper. One conversation, not three.
3. **Clear the `DailyStatsRepository` worker→domain move** off `fix/platform/dashboard-live-summaries` — land it or discard it. PR 4 touches the same area.

## PRs

### 1 · `feat/platform/ui-tabs` — no deps, not even the contract

Promote `frontend/src/features/tickets/detail/TicketDetailTabs.tsx` to `frontend/src/components/ui/tabs.tsx`. Its own docblock says to do this the moment a second screen needs tabs; this is that moment.

Keep the `TicketDetailTabs` exports intact as a thin wrapper so no ticket-detail import changes — that is what keeps this a Stream C sign-off rather than a Stream C refactor. Storybook entry, keyboard tests (arrow keys move between tabs, Home/End, roving tabindex, `aria-selected`).

### 2 · `feat/platform/dashboard-tab-shell` — needs 1

Three things, and they are the seams for the whole feature:

- `DashboardPage.tsx` becomes a shell over `tabs/{today,overview,weekly,analytics}/`, with `tab` joining `useDashboardFilters` and URL-addressable `?tab=` (default `today`).
- **The Analytics move is verbatim.** All 6 KPI cards, 14 widgets, chooser, drag-reorder and `AsOfNotice` move unchanged. Do not tidy anything on the way past. `DashboardVariantTest` reads `useDashboardVariant.ts` literally — keep the path and the literals.
- **Stub all three new routes in `DashboardController`** (`/today`, `/overview`, `/weekly`) returning empty DTOs, registered in `RouteInventory` with permission-matrix rows for all six roles. Dev 2 fills two of the bodies. Once this lands, nobody edits the controller again.

After this PR, `DashboardPage.tsx` and `useDashboardFilters.ts` are frozen — Dev 2 mounts into their tab folders and never touches the shell.

### 3 · `feat/platform/ticket-start-finish-stamps` — no deps, land early

`ticket_cycles.started_at` / `finished_at` as `DATETIME(6) NULL`, indexes, and a backfill from `ticket_history` STATUS_CHANGED rows. Per-cycle, so a reopened ticket counts again in its new cycle rather than being permanently "finished".

Protected-table adjacency: this is Stream A review territory even though you are Stream A — get a second pair of eyes on the migration. The stamping at transition time is Divyansh's paired PR; yours is schema and backfill only.

### 4 · `feat/platform/today-stats-schema` — needs 3 · **the critical path**

The single most-depended-on PR in the feature. Dev 2's PRs 9 and 11 both wait on it.

- `daily_ticket_stats`: `ns_total, ns_overdue, ns_due_today, wip_total, wip_updated_today, wip_near_delay, wip_delayed, started_today, finished_early, finished_on_time, finished_late, blocked_on_hold, blocked_awaiting_info, pending_review` as `INT NULL` (upsert-table convention), plus `open_by_role JSON` on a second-pass refresh following the `refreshTypeCounts` pattern.
- `resource_daily_stats`: the same counters as `INT NOT NULL DEFAULT 0` (delete-and-rewrite convention).
- **`WorkingHoursService.nextWorkingDay(LocalDate)`** with its unit test in *this* PR, not a follow-up. Near delay means due on or before the next working day — weekends and org holidays included. Compute it once per worker pass and bind it into the SQL; do not call it per row.
- A new `TodayStatsRepository` class, and **all three** new call sites wired into `StatsRefreshWorker` — yours plus the two empty classes Dev 2 will fill (`WeeklyStatsRepository`, `ModuleStatsRepository`). Adding them here means Dev 2 never edits the worker.
- Derivables (`wip_on_time`, `wip_not_updated`) are computed on read, never stored.

**Backfill honesty:** activity-dependent columns stay NULL for past days, following the `wip_by_stage` precedent. Started and finished are backfillable from history; `updated_at` is not, and pretending otherwise puts invented numbers on a dashboard.

`StatsRefreshIT` covers every new counter, near-delay across a seeded holiday *and* a weekend, pending-review dedup, `open_by_role` including unassigned, and an idempotent second recompute.

### 5 · `feat/platform/ticket-list-today-params` — needs H1 + PR 3 · **Stream C files, land early**

`statusCategory`, `statuses` (CSV), `updatedFrom/To`, `startedFrom/To`, `finishedFrom/To`, `pendingReview` on `TicketListController`, implementing the params Dev 2 already declared in the contract. Dev 2's Weekly accordion (PR 13) and the `module-open` drill-downs (PR 14) need these as much as your sections do — which is why this lands early despite serving your tab last.

**An IT per parameter proving it actually filters.** Spring drops unknown query params silently, so a mistyped param name is a filter that reports success and does nothing — and every drill-down URL in the feature is built from this list.

### 6 · `feat/platform/today-endpoint` — needs H1, 4, 5

`TodayProgressService` behind `GET /dashboard/today`: the seven cards including the `todaysWork` and `overdue` roll-ups, the Open Issues by-role card, `resources[]` MIS rows, and a `drillDown` URL on **every** figure.

Own-work variant for Developer/QA/Deployment: own figures only, no MIS table, no by-role card. Pending Review resolves review stages from the stage master — never hardcode VERIFY/SIGNOFF.

Implement the response Dev 2 froze at H1 — if it turns out to be wrong, that is a message to Dev 2 for an amendment, never an edit of your own to `openapi.yaml`. Register the service in `DrillDownContractTest.SOURCES` (append to the end of the list). ITs on the `DashboardScopeIT` pattern: developer isolation, PM project narrowing, out-of-scope returns **404, not 403**.

### 7 · `feat/platform/today-tab-cards` — needs 2, 6

Seven `TodaySummaryCard`s in prototype order, led by Today's Work and Overdue, plus `OpenIssuesByRoleCard` with DEV/QA/PM/SUP/DEP/Unassigned chips. `KpiCard`'s shape does not fit multi-figure cards — that is why `TodaySummaryCard` is a new component, not a variant.

Started Today and Finished Today are **not** cards. They are sections below, and their columns stay in the MIS table. That was a product decision; the prototype shows the outcome.

Every sub-figure and every role chip is a real `<button>` with visible focus that opens the S-06 `DrillDownPanel`.

### 8 · `feat/platform/today-tab-mis-sections` — needs 7

`AssigneeMisTable` on the ui `Table` — one row per resource, ten metric columns, danger tokens on problem cells when > 0, every cell a drill-down target keyed by assignee *and* metric.

Then six collapsible `TodaySections` with ticket tables, **lazy-fetching on expand** (limit ≈ 50, "View all" deep-links to `/tickets?…`). Query construction goes in a pure `todaySectionQueries.ts` with its own unit tests — it is the piece most likely to silently disagree with the card above it.

## Done means

Cards match hand-run SQL. Near-delay behaves correctly either side of a configured holiday. A Developer login gets the own-work variant. Clicking any figure opens the drill-down with **exactly** the tickets that figure counted. Tab deep links work. Side by side with the prototype: same cards, same order, same interactions.
