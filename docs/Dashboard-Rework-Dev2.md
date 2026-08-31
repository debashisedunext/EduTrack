# Dashboard Rework — Dev 2 backlog

**Overview, Weekly and `module-open` — full stack, migration to pixel.** Boundary rules live in [`Dashboard-Rework-Split.md`](Dashboard-Rework-Split.md); behaviour lives in [`Dashboard-Rework-Plan.md`](Dashboard-Rework-Plan.md); appearance lives in `docs/prototype/index.html`. Where this file and the prototype disagree on how something looks, the prototype wins.

You have the harder half on purpose: the entire API contract, three verticals against Dev 1's one, the nested roll-up accordion, ISO-week maths, two keyboard-navigable charts and a summary table of your own. You cross no stream boundaries — everything you touch is Stream A's, and Dev 1 carries all three Stream C conversations.

## Day one

### H1 — the contract, and it is entirely yours

You own `contracts/openapi.yaml` outright and every regeneration of the client. On day one you author and commit **the whole delta, both halves**: your `/dashboard/overview`, `/dashboard/weekly` and the `module-open` widget key, *and* Dev 1's `/dashboard/today` response and the seven new `GET /tickets` params. Dev 1 hands you their requirements in writing that morning and then stays out of the file.

One author means the most contended file in the feature cannot conflict at all. It also means **Dev 1 cannot start PR 5 or PR 6 until you are done** — and PR 5 is one of the two PRs *you* are waiting on. A slow contract comes back to you: it delays PR 5, which delays your PR 14 Ready flip and your PR 13. Finish it on day one.

After H1, amendments are requests, not edits: when Dev 1 finds the `/today` shape wrong mid-build, they message you, you amend and regenerate, they rebase. Expect two or three of these.

Then start on **the pure modules and `module-open`** below — they depend on nothing at all, so you are never sitting idle waiting for Dev 1's schema.

**Never touch:** `DashboardPage.tsx`, `useDashboardFilters.ts`, `DashboardController.java` (Dev 1 stubs your two routes in their PR 2), `StatsRefreshWorker` (Dev 1 wires your call sites), or `DailyStatsRepository`. `openapi.yaml` is the exception that proves the rule — it is entirely yours, so nobody else touches it.

## Start here — no dependencies

### `groupTickets.ts` — the hardest thing in the feature

Pure module, unit tests first, no React. Nests a flat ticket list **Client → Module → Severity → rows** with roll-up counts on every level and a truncation notice at the 200-row cap.

Get the edge cases into tests before you write the component that renders it: a ticket with no module, a client with a single ticket, and a level whose children are all truncated — the roll-up count must still be the *true* count, not the rendered one. Ordering has to be stable across recomputes. Every level header is a drill-down target, so a group count that disagrees with what the drill-down returns is a visible bug, not a rounding artefact.

### `weeklyRange.ts`

Pure ISO-Monday maths in UTC. This week / last week, week start and end, and the prior-week window for deltas. Test the year boundary and a week starting in late December — ISO week 1 is the week containing the first Thursday, which is not what a naive `startOfWeek` gives you.

### 14 · `feat/platform/module-open-widget` — buildable immediately, Ready only after Dev 1's PR 5

The whole vertical, yours end to end:

- **`module_daily_stats`** on the `client_daily_stats` precedent: `stat_date, project_id, module_id, open_wip, open_overdue, open_not_started, computed_at`.
- **`ModuleStatsRepository`** — the class Dev 1 has already wired into `StatsRefreshWorker` for you, so you fill the body and touch no shared file.
- The `module-open` branch in `WidgetService` plus a 15th `WIDGET_CATALOG` entry (**append** to the end of the list, never reorder), which makes the chooser and drag-reorder work for free.
- `ModuleOpenBar` — horizontal stacked bar per module, segments WIP / Overdue / Not Started.

**The segments are disjoint and Overdue takes precedence**: an overdue WIP ticket is counted once, under Overdue. Put that in the SQL *and* in a test, or the bars over-count and nobody notices for a month.

Each point carries a `drillDown` with `moduleId` plus state params. Register the service in `DrillDownContractTest.SOURCES` (append).

**One dependency, in the drill-downs only.** `GET /tickets` already implements `moduleId` and `isDelayed`, so the Overdue segment is expressible today — but Not Started is NEW *or* REOPENED and WIP is the whole IN_PROGRESS category, and the existing single-value `status` param cannot express either. Both need `statusCategory` from Dev 1's PR 5. Everything else here — migration, repository, `WidgetService` branch, catalog entry, the chart — is yours alone and can be finished first; `DrillDownContractTest` will fail until PR 5 lands, so build it now and mark it Ready then.

## After H2 — once Dev 1's PR 4 lands the counters

### 9 · `feat/platform/overview-endpoint`

`OverviewService` behind `GET /dashboard/overview?projectId&from&to&assigneeId` — Dev 1 has already stubbed the route:

- Four cards: Total / Pending (TODO) / In Progress / Completed (DONE) for the selected range.
- `assignees[]`, top ten, `{userId, displayName, inProgress, overdue, notStarted}` — reading `assigned_in_progress` / `assigned_delayed` from `resource_daily_stats`, with not-started derived.
- `distribution` for the donut: three buckets with counts.

**The Top Assignees bar shows open state, not completed-in-range.** It is the one figure on this tab that answers "what is on people's plates right now" rather than "what happened in the window", and the intuitive version is the wrong one. Per-state `drillDown` on every segment.

ITs on the `DashboardScopeIT` pattern — developer isolation, PM project narrowing, out-of-scope **404, not 403** — plus `RouteAuthorizationTest` rows for all six roles. Append to `DrillDownContractTest.SOURCES`, then regenerate the client.

### 10 · `feat/platform/overview-tab-ui` — needs 2, 9

Four cards, `TopAssigneesBar` (horizontal stacked: In Progress / Overdue / Not Started) and `StatusDistributionDonut` (half-donut, three buckets, legend with counts and percentages). Recharts, colours from `chartTokens.ts` only — never a hue that is not a token.

**Every arc, legend row and bar segment is a real `<button>`** with visible focus that opens the drill-down. Recharts will happily hand you a clickable `<path>` that no keyboard can reach; that does not pass. ARIA labels on both charts.

### 11 · `feat/platform/weekly-stats-schema` — needs 4

`daily_ticket_stats.open_pct_sum, delay_days_sum, open_due_next_7` and `resource_daily_stats.pct_sum, delay_days_sum`, plus **`WeeklyStatsRepository`** — again a class Dev 1 has already wired into the worker.

Sums in storage, not averages: the average is computed on read so it stays correct when the denominator moves. Backfill honesty applies — activity-dependent columns stay NULL for past days rather than being invented.

### 12 · `feat/platform/weekly-endpoint` — needs 11

`WeeklyProgressService` behind `GET /dashboard/weekly?projectId&weekStart&assigneeId`: four cards — average progress percent of open tickets via `pct_complete`, due this week plus finished so far, delayed versus last week, average delay days — **each with its prior-week delta**.

The delta is the subtle part: the comparison window is the same ISO week offset by seven days, and a week with no prior data shows *no* delta rather than a fabricated zero. **No S-Curve** — that was decided against; do not add it back because the layout looks sparse without it. Append to `DrillDownContractTest.SOURCES`, regenerate.

### 13 · `feat/platform/weekly-tab-ui` — needs 2, 5, 12

`WeekPicker` (This week / Last week, ISO Monday, UTC) over `weeklyRange.ts`, the four cards, and five accordion sections — Critical should-have-started · Not Started · WIP Updated This Week · Finished This Week · WIP Not Finishing/Overdue — each rendering `GroupedTicketAccordion` over `groupTickets.ts`.

`weekStart` is URL-addressable: `?tab=weekly&weekStart=…` must deep-link. Group headers are drill-down targets, and **accordion group totals must equal the section badge count** — if they diverge, the two are querying differently and that is a defect to fix, not a discrepancy to explain.

## Done means

Every card figure matches a hand-run SQL count. Accordion group totals equal section badge counts. `module-open` segments sum to each module's open total with no double-count. Every arc, segment, legend row and group header opens the drill-down with exactly the tickets it counted — by mouse **and** by keyboard. `?tab=weekly&weekStart=…` deep-links. Side by side with the prototype: same cards, same order, same interactions.

**Mark a UI PR Ready only after its endpoint is in `develop` and you have seen the tab work against the real backend and worker.** MSW gets the screen written; it does not get the PR merged.
