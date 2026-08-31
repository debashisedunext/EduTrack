# Dashboard Rework — Build Spec & Handoff

**Owner: Stream A (assigned: Shivender). The visual authority is the prototype — `docs/prototype/index.html`, Dashboard screen. Build it exactly as it looks and behaves there; where this document and the prototype disagree on appearance or interaction, the prototype wins. Where the prototype is silent on data mechanics, this document wins.**

Open the prototype in a browser, click through all four tabs, click the values (they open the S-06 drill-down panel), expand the sections and accordions, switch the week toggle — everything you see is the acceptance criterion.

## What is being built

Rework `/dashboard` (screen S-05) from a single page into **four tabs**, URL-addressable as `?tab=` (default `today`):

### Tab 1 — Today's Progress (default)

Seven summary cards, in this order, **led by two roll-up cards**:

1. **Today's Work** — not started / on time / WIP / overdue (the whole of today's plate in one card)
2. **Overdue** — not started / WIP (the two ways a ticket can be late; the two figures sum to the overdue total)
3. **Not Started** — overdue start / due today / total
4. **WIP** — total / updated today / not updated
5. **WIP Breakdown** — near delay / delayed / on time
6. **Blocked** — On Hold and Awaiting Info as two labelled sub-figures
7. **Pending Review** — RESOLVED-not-CLOSED **plus** tickets in verify/sign-off stages, one combined count

Plus the **Open Issues card**: total open (not-closed) tickets with **by-role chips** — DEV / QA / PM / SUP / DEP / Unassigned — showing who currently holds them.

**Started Today and Finished Today are deliberately not cards** (removed by product decision). They remain as collapsible sections below, and their stats columns stay in the summary tables for the MIS and sections.

Below the cards: the **Assignee MIS table** (one row per resource: overdue start, due today, not started, WIP, updated, near delay, delayed, on time, finished today, finished late — red on the problem columns when > 0), then six **collapsible sections** with ticket tables (Not Started overdue/due-today · Started Today · Finished Today · WIP Updated Today · WIP Not Updated · Blocked/On Hold), rows deep-linking to ticket detail.

### Tab 2 — Ticket Overview

Four cards (Total / Pending=TODO / In Progress / Completed=DONE for the selected range) · **Top Assignees** horizontal stacked bar showing **open state per assignee: In Progress / Overdue / Not Started** (not completed-in-range) · **Status Distribution** half-donut, three buckets, legend with counts and %.

### Tab 3 — Weekly Progress

Week picker (This week / Last week, ISO Monday, UTC). Four cards (avg progress % of open tickets via `pct_complete`, due this week + finished so far, delayed vs last week, avg delay days) — **no S-Curve, by decision**. Five accordion sections (Critical should-have-started · Not Started · WIP Updated This Week · Finished This Week · WIP Not Finishing/Overdue), each a **nested accordion grouped Client → Module → Severity → ticket rows** with roll-up counts on every level.

### Tab 4 — Analytics

The existing dashboard moved verbatim (6 KPI cards + 14 widgets + chooser + drag-reorder + `AsOfNotice`), **plus one new widget: `module-open` — Module-wise Total Open Tickets**: horizontal stacked bar per module, segments WIP / Overdue / Not Started (disjoint; Overdue takes precedence), a 15th `WIDGET_CATALOG` entry + `WidgetService` branch so chooser/reorder work for free.

### Drill-down everywhere — a requirement, not a flourish

**Every value on every tab is clickable and opens the right slide-over (the existing S-06 `DrillDownPanel`) listing the tickets behind that value**: every card sub-figure, every Open Issues role chip, every MIS cell (assignee + metric), every Top Assignees bar segment, every donut arc and legend row, every Weekly card, every `module-open` segment, and Weekly group headers. All targets are real `<button>`s with visible focus (keyboard accessible). Server-side, every figure in every DTO carries a `drillDown` URL built with **only parameters `GET /tickets` implements** — enforced by adding every new URL-emitting service to `DrillDownContractTest.SOURCES`.

## Key semantics

- Not Started = `statuses.category` TODO (NEW + REOPENED); overdue-to-start = `planned_close_date < today`.
- WIP = category IN_PROGRESS; updated today = `updated_at` today; delayed = `is_delayed`; **near delay = due on/before the next working day** — working calendar (weekends + org holidays) via a new `WorkingHoursService.nextWorkingDay(LocalDate)`, computed once per worker pass and bound into the SQL.
- Started/Finished Today come from new per-cycle stamps (`ticket_cycles.started_at` / `finished_at`) so a reopened ticket counts again in its new cycle; backfilled from `ticket_history` STATUS_CHANGED rows.
- Pending Review resolves review stages from the stage master — never hardcode VERIFY/SIGNOFF.
- "Today" is the UTC civil day; figures are ≤5 min stale; show it with the existing `AsOfNotice`.

## Data: no live COUNT(*) — summary tables only

All timestamped Flyway migrations in `backend/domain/src/main/resources/db/migration/`; worker recompute in `backend/worker/.../stats/DailyStatsRepository`:

1. `ticket_cycles.started_at/finished_at DATETIME(6) NULL` + indexes + history backfill. Protected-table adjacency → Stream A review; the transition-time stamping is a small **paired Stream C PR**.
2. `daily_ticket_stats` new `INT NULL` columns (upsert-table convention): `ns_total, ns_overdue, ns_due_today, wip_total, wip_updated_today, wip_near_delay, wip_delayed, started_today, finished_early, finished_on_time, finished_late, blocked_on_hold, blocked_awaiting_info, pending_review` + `open_by_role JSON` (second-pass refresh, `refreshTypeCounts` pattern). Derivables (`wip_on_time`, `wip_not_updated`) are computed, never stored.
3. `resource_daily_stats` same counters as `INT NOT NULL DEFAULT 0` (delete+rewrite convention) — feeds the MIS table and the Top Assignees bar (`assigned_in_progress` / `assigned_delayed` / derived not-started).
4. Weekly: `daily_ticket_stats.open_pct_sum, delay_days_sum, open_due_next_7`; `resource_daily_stats.pct_sum, delay_days_sum`.
5. **`module_daily_stats`** (new table on the `client_daily_stats` precedent): `stat_date, project_id, module_id, open_wip, open_overdue, open_not_started, computed_at` — feeds `module-open`.

Backfill honesty: activity-dependent columns for past days stay NULL (`wip_by_stage` precedent); started/finished are backfillable from history, `updated_at` is not. Ticket **lists** (sections, accordions, drill-downs) are live paginated queries via `GET /tickets` — the established drill-down precedent; no dashboard *figure* ever comes from a live count.

## Endpoints

On the existing `DashboardController` (`/api/v1/dashboard`), all through `DashboardScope`, 404 out-of-scope, registered in `RouteInventory` with permission-matrix rows for all six roles:

- `GET /dashboard/today` → `TodayProgressService`: cards (incl. `todaysWork` and `overdue` roll-ups) + `resources[]` MIS rows + drillDown URLs on every figure. Own-work variant (Developer/QA/Deployment): own figures only, no MIS table, no by-role card.
- `GET /dashboard/overview?projectId&from&to&assigneeId` → `OverviewService`: 4 cards + top-10 `assignees[]` (`{userId, displayName, inProgress, overdue, notStarted}`, each with per-state drillDown) + `distribution`.
- `GET /dashboard/weekly?projectId&weekStart&assigneeId` → `WeeklyProgressService`: 4 cards with prior-week deltas.
- `module-open` as a `WidgetService` branch reading `module_daily_stats`, each point carrying `drillDown` with `moduleId` + state params.
- **New `GET /tickets` params** (Stream C's `TicketListController`, coordinated PR — land these first): `statusCategory`, `statuses` (CSV), `updatedFrom/To`, `startedFrom/To`, `finishedFrom/To`, `pendingReview`.
- Contract-first: update `contracts/openapi.yaml`, then `npm run api:generate`. Never hand-edit `frontend/src/api/generated/`.

## Frontend

- **Promote `TicketDetailTabs` → `components/ui/tabs.tsx`** (+ Storybook + tests) — its own docblock says to do this when a second screen needs tabs. Keep `TicketDetailTabs` exports intact (thin wrapper). Tab id joins `useDashboardFilters` as `tab`.
- `DashboardPage.tsx` becomes a shell; content in `tabs/{today,overview,weekly,analytics}/`. Analytics is a verbatim move — keep `useDashboardVariant.ts` path and literals intact (`DashboardVariantTest` reads that file).
- New components per prototype: `TodaySummaryCard` (multi-figure card — `KpiCard`'s shape doesn't fit), `OpenIssuesByRoleCard`, `AssigneeMisTable` (ui `Table`, danger tokens on problem cells), `TodaySections` (lazy fetch on expand, limit≈50, "View all" → `/tickets?…`), `todaySectionQueries.ts` (pure, unit-tested), `TicketOverviewTab` + `TopAssigneesBar` + `StatusDistributionDonut` (Recharts, `chartTokens.ts`), `WeekPicker` + `weeklyRange.ts` (pure ISO-Monday math), `GroupedTicketAccordion` + pure `groupTickets.ts` (Client → Module → Severity nesting, counts, truncation notice at the 200-row cap), `ModuleOpenBar` for the new widget.

## PR breakdown (each < 400 lines, draft PRs, batch-integrated as usual)

| # | Branch | Contents | Deps |
|---|---|---|---|
| 1 | `feat/platform/ui-tabs` | tabs component + stories/tests | — |
| 2 | `feat/platform/dashboard-tab-shell` | `?tab=`, shell, Analytics move | 1 |
| 3 | `feat/platform/ticket-start-finish-stamps` | migration + backfill (+ paired Stream C stamping PR) | — |
| 4 | `feat/platform/today-stats-schema` | migrations 2+3, worker SQL, `nextWorkingDay`, `StatsRefreshIT` | 3 |
| 5 | `feat/platform/ticket-list-today-params` | contract + list params + ITs (Stream C files — coordinated) | 3 |
| 6 | `feat/platform/today-endpoint` | `TodayProgressService` + ITs + `DrillDownContractTest.SOURCES` + regen | 4,5 |
| 7 | `feat/platform/today-tab-cards` | cards row incl. Today's Work + Overdue, variant handling | 2,6 |
| 8 | `feat/platform/today-tab-mis-sections` | MIS table + sections | 7 |
| 9 | `feat/platform/overview-endpoint` | `OverviewService` + IT + regen | 4 |
| 10 | `feat/platform/overview-tab-ui` | cards, open-state assignee bars, donut | 2,9 |
| 11 | `feat/platform/weekly-stats-schema` | migration 4 + worker + IT | 4 |
| 12 | `feat/platform/weekly-endpoint` | `WeeklyProgressService` + IT + regen | 11 |
| 13 | `feat/platform/weekly-tab-ui` | picker, cards, grouped accordion | 2,5,12 |
| 14 | `feat/platform/module-open-widget` | `module_daily_stats` migration + worker + widget branch + `ModuleOpenBar` + catalog entry | 4 |

PRs 3 and 5 are the cross-stream touchpoints — land them earliest; if the stage master needs a review-stage flag, that's a small Stream B-reviewed migration — surface it early via notify-stream.

## Tests

- Worker `StatsRefreshIT`: every new counter incl. near-delay across a seeded holiday + weekend; pending-review dedup; `open_by_role` incl. unassigned; module stats; idempotent recompute. `WorkingHoursServiceTest` for `nextWorkingDay`.
- API ITs per endpoint on the `DashboardScopeIT` pattern: developer isolation, PM project narrowing, out-of-scope 404. `RouteAuthorizationTest` rows for all six roles on the three new routes.
- Contract: every new URL-emitting service registered in `DrillDownContractTest.SOURCES`; list-param ITs proving each new `/tickets` param filters (Spring drops unknown params silently).
- Frontend: tabs keyboard tests, pure-function tests (`weeklyRange`, `groupTickets`, `todaySectionQueries`), MSW tests for lazy sections + MIS highlighting + drill-down opens; Storybook for tabs, `TodaySummaryCard`, `GroupedTicketAccordion`.

## Working rules that apply (CLAUDE.md — read it, these bite)

- Branch from `develop`, rebase daily, conventional commits, **task IDs only in commit bodies until a task is finished**.
- Open every PR as a **draft**; mark Ready when your unit/smoke tests are green; CI is the authority; **you never merge** — Claude integrates in batches.
- Timestamp-versioned migrations only; never edit an applied one; `tickets`/`ticket_history`/effort/transition-adjacent migrations need Stream A review.
- No live `COUNT(*)` for dashboard figures; UTC everywhere; working-calendar maths for anything due/SLA-shaped; only your stream's paths, or sign-off.

## Verification (definition of done for the whole feature)

Seed dev data, run backend + worker, open `/dashboard`: tab deep links work (`?tab=weekly&weekStart=…`); every card figure matches a hand-run SQL count; near-delay behaves correctly around a configured holiday; accordion group totals equal section badge counts; a Developer login sees the own-work variant; **clicking any figure opens the drill-down panel with exactly the tickets the figure counted**; drill-down links open pre-filtered ticket lists. Compare each tab side-by-side with the prototype — same cards, same order, same interactions.
